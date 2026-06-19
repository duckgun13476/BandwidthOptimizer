package net.minecraft.resources;

public final class ResourceLocation implements Comparable<ResourceLocation> {
    private final Identifier identifier;

    private ResourceLocation(Identifier identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier cannot be null");
        }
        this.identifier = identifier;
    }

    public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
        return new ResourceLocation(Identifier.fromNamespaceAndPath(namespace, path));
    }

    public static ResourceLocation fromIdentifier(Identifier identifier) {
        return new ResourceLocation(identifier);
    }

    public Identifier asIdentifier() {
        return identifier;
    }

    public String getNamespace() {
        return identifier.getNamespace();
    }

    public String getPath() {
        return identifier.getPath();
    }

    @Override
    public String toString() {
        return identifier.toString();
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value instanceof ResourceLocation other) {
            return identifier.equals(other.identifier);
        }
        if (value instanceof Identifier other) {
            return identifier.equals(other);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    @Override
    public int compareTo(ResourceLocation other) {
        return identifier.compareTo(other.identifier);
    }
}
