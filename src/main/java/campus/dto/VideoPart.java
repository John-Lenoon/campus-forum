package campus.dto;

/** Immutable input for file assembly, independent of database mapping. */
public record VideoPart(int index, int size, String sha256) {}
