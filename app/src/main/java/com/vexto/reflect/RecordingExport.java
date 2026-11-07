package com.vexto.reflect;

import android.content.Intent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;

/** Documents produced from a saved recording and its optional transcript. */
enum RecordingExport {
    JSON(4, "Экспортировать JSON", "export_braces", "application/json", "json"),
    MARKDOWN(5, "Экспортировать MD", "export_file", "text/markdown", "md"),
    AUDIO(6, "Экспортировать звук", "export_voice", null, null);

    private static final Pattern MARKDOWN_PUNCTUATION = Pattern.compile("\\p{Punct}");
    final int request;
    final String label, icon;
    private final String mime, extension;

    RecordingExport(int request, String label, String icon, String mime, String extension) {
        this.request = request;
        this.label = label;
        this.icon = icon;
        this.mime = mime;
        this.extension = extension;
    }

    static RecordingExport forRequest(int request) {
        for (RecordingExport export : values()) {
            if (export.request == request) return export;
        }
        return null;
    }

    Intent createDocument(RecordingMetadata audio, String title) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime(audio)).putExtra(Intent.EXTRA_TITLE, filename(audio, title));
    }

    String mime(RecordingMetadata audio) {
        String type = this == AUDIO ? audio.mime() : mime;
        return type == null ? "application/octet-stream" : type;
    }

    String filename(RecordingMetadata audio, String title) {
        String suffix = this == AUDIO ? audio.extension() : extension;
        String name = title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "vexto";
        if (!name.toLowerCase(Locale.ROOT).endsWith("." + suffix)) name += "." + suffix;
        return name;
    }

    void write(OutputStream stream, File audio, ReflectTranscript transcript, String title) throws IOException {
        write(stream, audio, transcript, title, java.util.Collections.emptyMap());
    }

    void write(OutputStream stream, File audio, ReflectTranscript transcript, String title,
               java.util.Map<String, String> names) throws IOException {
        if (this == AUDIO) {
            Files.copy(audio.toPath(), stream);
            return;
        }
        if (transcript == null || transcript.lines.isEmpty()) throw new IOException("No transcript to export");
        if (this == JSON) {
            transcript.writeSubmission(stream);
        } else {
            Writer markdown = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            markdown.write("# " + escape(title).replace('\n', ' ').replace('\r', ' ') + "\n\n");
            for (ReflectTranscript.Line line : transcript.lines) {
                String name = names.getOrDefault(line.speakerId, line.speakerName);
                String speaker = escape(name == null ? line.speakerId : name + " (" + line.speakerId + ")")
                        .replace('\n', ' ').replace('\r', ' ');
                markdown.write("**" + timestamp(line.startMs) + " – " + timestamp(line.endMs)
                        + " · " + speaker + "**\n\n> ");
                markdown.write(escape(line.text).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n> "));
                markdown.write("\n\n");
                if (line.emotionProbabilities != null) {
                    var emotions = line.emotionProbabilities;
                    markdown.write("Эмоции (вероятности): нейтральная=" + emotions.getNeutral()
                            + "; радость=" + emotions.getJoy() + "; грусть=" + emotions.getSadness()
                            + "; злость=" + emotions.getAnger() + "\n\n");
                }
            }
            markdown.flush();
        }
    }

    private static String timestamp(int millis) {
        int seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", seconds / 3600,
                seconds / 60 % 60, seconds % 60, millis % 1000);
    }

    private static String escape(String text) {
        return MARKDOWN_PUNCTUATION.matcher(text).replaceAll("\\\\$0");
    }
}
