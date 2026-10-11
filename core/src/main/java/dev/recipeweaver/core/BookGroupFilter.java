package dev.recipeweaver.core;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Case-insensitive player-book group selection; an empty group is selectable. */
final class BookGroupFilter {
    private BookGroupFilter() { }

    static List<String> available(Collection<String> groups) {
        TreeSet<String> ordered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String group : groups) ordered.add(normalize(group));
        return List.copyOf(ordered);
    }

    static String selected(String requested, List<String> available) {
        if (requested == null) return null;
        String normalized = normalize(requested);
        for (String group : available) {
            if (group.equalsIgnoreCase(normalized)) return group;
        }
        return null;
    }

    static String next(String current, List<String> available) {
        if (available.isEmpty()) return null;
        String selected = selected(current, available);
        if (selected == null) return available.get(0);
        int index = available.indexOf(selected);
        return index + 1 < available.size() ? available.get(index + 1) : null;
    }

    static boolean accepts(String selected, String group) {
        return selected == null || normalize(selected).equalsIgnoreCase(normalize(group));
    }

    static String sortKey(String group) { return normalize(group); }

    private static String normalize(String group) {
        return group == null ? "" : group.strip().replaceAll("\\s+", " ");
    }
}
