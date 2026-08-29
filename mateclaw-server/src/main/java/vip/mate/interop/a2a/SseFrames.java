package vip.mate.interop.a2a;

import java.util.ArrayList;
import java.util.List;

public final class SseFrames {

    private SseFrames() {
    }

    public record Frame(String event, String data) {
    }

    public static List<Frame> parse(String input) {
        List<Frame> frames = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return frames;
        }
        String event = "message";
        List<String> dataLines = new ArrayList<>();
        String[] lines = input.split("\\R", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                flush(frames, event, dataLines);
                event = "message";
                dataLines = new ArrayList<>();
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                dataLines.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        flush(frames, event, dataLines);
        return frames;
    }

    private static void flush(List<Frame> frames, String event, List<String> dataLines) {
        if (!dataLines.isEmpty()) {
            frames.add(new Frame(event == null || event.isBlank() ? "message" : event,
                    String.join("\n", dataLines)));
        }
    }
}
