package vip.mate.agent.runtime.dsh;

public record DshToolDescriptor(String name, String description, String inputSchema) {
    public DshToolDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema;
    }
}
