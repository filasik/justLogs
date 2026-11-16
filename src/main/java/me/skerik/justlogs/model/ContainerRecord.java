package me.skerik.justlogs.model;

import org.jspecify.annotations.Nullable;

/**
 * Items moved into ({@code DEPOSIT}) or out of ({@code WITHDRAW}) a container. {@code itemData} is the serialized item
 * (amount 1) for items with a name, enchantments or other data, null for plain items.
 */
public record ContainerRecord(int time, Actor actor, Action action, String world, int x, int y, int z,
                              String holderMaterial, String material, int amount,
                              byte @Nullable [] itemData) implements LogRecord {
}
