package vip.mate.workflow.api;

import vip.mate.workflow.compiler.CompileError;

import java.util.List;

/**
 * Response shape for compile failures returned from publish / preview-compile
 * endpoints. Surfaces every diagnostic at once so the front-end editor can
 * highlight all offending fields in a single round trip; mirroring
 * {@link CompileError} preserves the path / code / message tuple the editor
 * expects.
 */
public record CompileErrorResponse(int errorCount, List<Item> errors) {

    public record Item(String code, String path, String message) {}

    public static CompileErrorResponse of(List<CompileError> errors) {
        List<Item> items = errors.stream()
                .map(e -> new Item(e.code(), e.path(), e.message()))
                .toList();
        return new CompileErrorResponse(items.size(), items);
    }
}
