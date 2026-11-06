"""Synthetic TextGrid/WAV fixtures only; stdlib, no network or model imports."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / 'prepare_russian_diarization.py'
SPEC = importlib.util.spec_from_file_location('prepare_russian_diarization', MODULE)
preparer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preparer)


def textgrid(tiers):
    text = 'File type = "ooTextFile"\nObject class = "TextGrid"\nitem []:\n'
    for index, (name, rows, declared_count) in enumerate(tiers, 1):
        text += (f'    item [{index}]:\n        class = "IntervalTier"\n'
                 f'        name = "{name}"\n        intervals: size = {declared_count}\n')
        for number, (start, end, label) in enumerate(rows, 1):
            label = label.replace('"', '""')
            text += (f'        intervals [{number}]:\n            xmin = {start}\n'
                     f'            xmax = {end}\n            text = "{label}"\n')
    return text


def words(rows, count=None):
    return textgrid([('Words', rows, len(rows) if count is None else count)]).encode('utf-8')


def wav_header(channels=2, rate=48000, bits=16, encoding=1, junk=False):
    align = channels * bits // 8
    fmt = struct.pack('<HHIIHH', encoding, channels, rate, rate * align, align, bits)
    chunks = b'fmt ' + struct.pack('<I', len(fmt)) + fmt
    if junk:
        chunks += b'JUNK' + struct.pack('<I', 3) + b'abc\0'  # odd chunk needs padding
    chunks += b'data' + struct.pack('<I', 100)
    return b'RIFF' + struct.pack('<I', len(chunks) + 4 + 100) + b'WAVE' + chunks


class PrepareRussianDiarizationTests(unittest.TestCase):
    def test_uses_lower_words_bounds_with_escaped_quotes_in_utf8_and_utf16(self):
        text = textgrid([
            ('EDU', [(0, 10, 'N-vE001')], 1),
            ('Words', [(.2, .5, 'слово "в кавычках"'), (1.2, 1.5, 'ещё')], 2),
            ('Collat', [(0, 10, '{laugh}')], 1),
        ])
        encodings = [text.encode('utf-8-sig'), text.encode('utf-16'),
                     b'\xfe\xff' + text.encode('utf-16-be')]
        for data in encodings:
            with self.subTest(bom=data[:3]):
                spans, audit = preparer.parse_words(data)
                self.assertEqual(spans, [(.2, .5), (1.2, 1.5)])
                self.assertEqual(audit['tier'], 'Words')
                self.assertEqual(audit['source_intervals'], 2)
                self.assertEqual(audit['kept_intervals'], 2)

    def test_excludes_silence_nonverbal_and_inhalation_but_keeps_filled_pause(self):
        rows = [(0, 1, '  '), (1, 2, '{laugh}'), (2, 3, '(ɥ)'),
                (3, 4, '(ə)'), (4, 5, 'ответ')]
        spans, audit = preparer.parse_words(words(rows))
        self.assertEqual(spans, [(3, 4), (4, 5)])
        self.assertEqual(audit['excluded_counts'],
                         {'silent':1, 'explicit_nonverbal':1, 'inhalation':1})
        self.assertEqual(audit['kept_intervals'], 2)

    def test_rejects_interval_count_mismatch_and_ambiguous_words_tiers(self):
        for count in (0, 2):
            with self.subTest(declared=count), self.assertRaisesRegex(ValueError, 'count mismatch'):
                preparer.parse_words(words([(0, 1, 'ответ')], count=count))
        for tiers in ([('EDU', [(0, 1, 'N-vE001')], 1)],
                      [('Words', [(0, 1, 'один')], 1), ('Words', [(0, 1, 'два')], 1)]):
            with self.subTest(tiers=tiers), self.assertRaisesRegex(ValueError, 'one lower-level Words'):
                preparer.parse_words(textgrid(tiers).encode())

    def test_rejects_nonfinite_negative_reversed_and_nonmonotonic_times(self):
        invalid_rows = [
            [('NaN', 1, 'ответ')], [(0, '1e999', 'ответ')],
            [(-1, 1, 'ответ')], [(2, 1, 'ответ')], [(1, 1, 'ответ')],
            [(1, 2, 'один'), (.5, 1.5, 'два')],
        ]
        for rows in invalid_rows:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                preparer.parse_words(words(rows))

    def test_wav_chunks_padding_and_missing_format_or_data_are_validated(self):
        header = wav_header(junk=True)
        fmt, offset = preparer.wav_format(header)
        self.assertEqual(fmt, (1, 2, 48000, 192000, 4, 16))
        self.assertEqual(offset, len(header))
        malformed = [b'not a WAV file',
                     b'RIFF' + struct.pack('<I', 20) + b'WAVEdata' + struct.pack('<I', 100),
                     wav_header()[:-8]]
        for header in malformed:
            with self.subTest(header=header), self.assertRaises(ValueError):
                preparer.wav_format(header)

    def test_unexpected_audio_format_stops_before_pcm_download_or_decode(self):
        annotation = words([(301, 304, 'пример')])
        pinned = {f'04{role}':preparer.digest(annotation) for role in 'NCR'}
        for kwargs in ({'channels':1}, {'rate':16000}, {'bits':8}, {'encoding':3}):
            with self.subTest(format=kwargs), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for role in 'NCR':
                    (root/f'pears04{role}-voc.TextGrid').write_bytes(annotation)
                decoder = mock.Mock()
                with mock.patch.dict(preparer.ANNOTATION_SHA256, pinned), \
                     mock.patch.object(preparer, 'fetch', return_value=(wav_header(**kwargs), {})) as fetch:
                    with self.assertRaisesRegex(ValueError, 'Unexpected shared-microphone format'):
                        preparer.prepare(root, '04', decoder)
                    fetch.assert_called_once_with(
                        f'{preparer.BASE}/pears04/Pears04W-au.wav', 0, 65535)
                    decoder.decode.assert_not_called()
                    self.assertEqual(list(root.glob('*.wav')), [])


if __name__ == '__main__':
    unittest.main()
