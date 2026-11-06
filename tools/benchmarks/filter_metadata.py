"""Filter local LLM keywords without modifying ASR or trusting model output.

python tools/benchmarks/filter_metadata.py --asr-json asr.json
    --model-output llm.stdout.txt --output metadata.json
Writes metadata.json and metadata.audit.json. Optional --audit overrides the
sidecar path. An invalid whole response writes empty keywords and returns 2;
individual rejected phrases are audited while valid phrases remain (exit 0).
Only JSON whitespace and one trailing [end of text] runtime marker are allowed.
Words are whitespace-separated units containing a Unicode letter or number;
Unicode letters, numbers, marks and connector punctuation protect boundaries.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

MAX_KEYWORDS = 3
MAX_WORDS = 5
RUNTIME_SUFFIX = '[end of text]'


class MetadataError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MetadataError('duplicate_json_key', 'Duplicate JSON object key')
        result[key] = value
    return result


def reject_constant(value):
    raise MetadataError('invalid_json_constant', f'Invalid JSON constant: {value}')


def parse_model_output(text):
    """Parse exactly one value and the one permitted optional runtime suffix."""
    if not isinstance(text, str):
        raise MetadataError('invalid_json', 'Model output must be text')
    decoder = json.JSONDecoder(object_pairs_hook=unique_object, parse_constant=reject_constant)
    try:
        value, end = decoder.raw_decode(text.lstrip(' \t\r\n'))
    except json.JSONDecodeError as error:
        raise MetadataError('invalid_json', 'Model output is not one JSON object') from error
    remainder = text.lstrip(' \t\r\n')[end:].strip(' \t\r\n')
    if remainder not in ('', RUNTIME_SUFFIX):
        raise MetadataError('unexpected_suffix', 'Unexpected text after JSON object')
    return value, remainder == RUNTIME_SUFFIX


def word_character(char):
    category = unicodedata.category(char)
    return category[0] in 'LNM' or category == 'Pc'


def literal_phrase(raw, phrase):
    """Require a complete Unicode word at either word-like outer edge."""
    start = raw.find(phrase)
    while start >= 0:
        end = start + len(phrase)
        left_ok = not (word_character(phrase[0]) and start and word_character(raw[start - 1]))
        right_ok = not (word_character(phrase[-1]) and end < len(raw) and word_character(raw[end]))
        if left_ok and right_ok:
            return True
        start = raw.find(phrase, start + 1)
    return False


def word_count(phrase):
    return sum(any(char.isalnum() for char in unit) for unit in re.findall(r'\S+', phrase))


def filter_metadata(asr, model_output):
    if not isinstance(asr, dict) or not isinstance(asr.get('text'), str):
        raise ValueError('ASR must be an object with string text')
    raw = asr['text']
    result = {'keywords': []}
    audit = {'schema_version': 1, 'status': 'rejected', 'model_output_json_valid': False,
             'model_output_fully_valid': False, 'runtime_suffix_removed': False,
             'accepted_count': 0, 'rejected': [], 'normalizations': [],
             'policy': {'maximum_keywords': MAX_KEYWORDS, 'words_per_phrase': [1, MAX_WORDS],
                        'matching': 'case-sensitive literal substring at Unicode word boundaries',
                        'normalization': 'strip outer whitespace from metadata candidates only'},
             'limitations': ['Literal extraction does not establish semantic relevance.',
                             'Rejected model output must not be described as valid metadata.']}
    try:
        payload, suffix = parse_model_output(model_output)
    except MetadataError as error:
        audit['fatal_error'] = {'code': error.code, 'message': str(error)}
        return result, audit
    audit['model_output_json_valid'] = True
    audit['runtime_suffix_removed'] = suffix
    if not isinstance(payload, dict):
        audit['fatal_error'] = {'code': 'root_not_object', 'message': 'Expected a JSON object'}
        return result, audit
    if set(payload) != {'keywords'}:
        audit['fatal_error'] = {'code': 'unexpected_fields', 'message': 'Expected only the keywords field'}
        return result, audit
    if not isinstance(payload['keywords'], list):
        audit['fatal_error'] = {'code': 'keywords_not_array', 'message': 'keywords must be an array'}
        return result, audit
    seen = set()
    for index, candidate in enumerate(payload['keywords']):
        reasons = []
        if not isinstance(candidate, str):
            reasons.append('not_string')
        else:
            phrase = candidate.strip()
            if phrase != candidate:
                audit['normalizations'].append({'index': index, 'action': 'strip_outer_whitespace'})
            if not phrase:
                reasons.append('empty')
            else:
                count = word_count(phrase)
                if not 1 <= count <= MAX_WORDS:
                    reasons.append('word_count_out_of_range')
                if not literal_phrase(raw, phrase):
                    reasons.append('not_literal_phrase')
                if phrase in seen:
                    reasons.append('duplicate')
                seen.add(phrase)
                if not reasons and len(result['keywords']) >= MAX_KEYWORDS:
                    reasons.append('keyword_limit_exceeded')
        if reasons:
            audit['rejected'].append({'index': index, 'candidate': candidate, 'reasons': reasons})
        else:
            result['keywords'].append(phrase)
    audit['accepted_count'] = len(result['keywords'])
    audit['model_output_fully_valid'] = not audit['rejected'] and not audit['normalizations']
    audit['status'] = 'accepted' if audit['model_output_fully_valid'] else 'filtered'
    return result, audit


def same_path(first, second):
    return (first.resolve() == second.resolve()
            or (first.exists() and second.exists() and first.samefile(second)))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asr-json', required=True, type=Path)
    parser.add_argument('--model-output', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--audit', type=Path)
    args = parser.parse_args(argv)
    audit_path = args.audit or args.output.with_name(args.output.stem + '.audit.json')
    try:
        inputs, outputs = [args.asr_json, args.model_output], [args.output, audit_path]
        if any(same_path(out, inp) for out in outputs for inp in inputs) or same_path(*outputs):
            parser.error('Output and audit must be distinct and must not overwrite either input')
        asr_bytes, model_bytes = [path.read_bytes() for path in inputs]
        asr = json.loads(asr_bytes.decode('utf-8-sig'), object_pairs_hook=unique_object,
                         parse_constant=reject_constant)
        result, audit = filter_metadata(asr, model_bytes.decode('utf-8-sig'))
        audit['input_sha256'] = {'asr_json': hashlib.sha256(asr_bytes).hexdigest(),
                                 'model_output': hashlib.sha256(model_bytes).hexdigest()}
        for path, value in ((args.output, result), (audit_path, audit)):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8'))
    except (ValueError, OSError) as error:
        print(f'Cannot filter metadata: {error}', file=sys.stderr)
        return 2
    return 2 if 'fatal_error' in audit else 0


if __name__ == '__main__':
    raise SystemExit(main())
