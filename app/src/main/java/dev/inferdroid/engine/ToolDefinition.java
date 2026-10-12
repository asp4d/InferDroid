package dev.inferdroid.engine;

/** A client-owned function and its JSON parameter schema. The engine never executes it. */
public final class ToolDefinition {
    public final String name;
    public final String description;
    public final String parametersJson;

    public ToolDefinition(String name, String description, String parametersJson) {
        this.name = name;
        this.description = description;
        this.parametersJson = parametersJson;
    }
}
