package dev.recipeweaver.api;

public enum StationKind {
    FURNACE(1),
    BLAST_FURNACE(1),
    SMOKER(1),
    CAMPFIRE(1),
    STONECUTTER(1),
    SMITHING_TRANSFORM(3),
    SMITHING_TRIM(3),
    ANVIL(2),
    GRINDSTONE(2),
    BREWING(2),
    CAULDRON(2);

    private final int slots;

    StationKind(int slots) { this.slots = slots; }
    public int slots() { return slots; }
    public boolean cooking() {
        return this == FURNACE || this == BLAST_FURNACE || this == SMOKER || this == CAMPFIRE;
    }
}
