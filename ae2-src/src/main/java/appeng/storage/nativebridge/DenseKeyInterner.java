/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2025, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.storage.nativebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

/**
 * Assigns dense {@code int} ids to keys grouped by primary-key identity.
 * <p>
 * AE2 keys may be fresh value objects for the same resource, while their primary keys are shared instances. Ids start
 * at 0 and grow monotonically until {@link #clear()} resets the interner.
 */
public final class DenseKeyInterner<T> {
    private final Reference2IntMap<T> ids = new Reference2IntOpenHashMap<>();
    /**
     * The canonical instance per id, keyed by the key's primary key.
     * <p>
     * AE2 keys are value objects: {@code AEItemKey.of(stack)} returns a fresh instance every call, while
     * {@code getPrimaryKey()} returns the shared {@code Item}. Interning by instance identity therefore gave one
     * logical resource several ids as soon as two code paths produced their own instance for it - which is exactly what
     * the network does, since a cell read and the native aggregate each build their own keys. The native side then
     * summed the resource correctly across those ids while the Java counter kept them apart, so an increment to one id
     * overwrote the other's amount. Interning by primary key, like {@code KeyCounter} does, gives one id per logical
     * resource.
     */
    private final Reference2IntMap<Object> idsByPrimaryKey = new Reference2IntOpenHashMap<>();
    private final List<T> keys = new ArrayList<>();
    private final Function<T, Object> primaryKey;

    public DenseKeyInterner(Function<T, Object> primaryKey) {
        this.primaryKey = Objects.requireNonNull(primaryKey, "primaryKey");
        ids.defaultReturnValue(-1);
        idsByPrimaryKey.defaultReturnValue(-1);
    }

    /**
     * @return the instance this interner already uses for {@code key}'s primary key, or {@code key} itself if it is the
     *         first one seen.
     */
    public T internCanonical(T key) {
        var id = intern(key);
        return keyOf(id);
    }

    public int intern(T key) {
        var existing = ids.getInt(key);
        if (existing >= 0) {
            return existing;
        }
        var pk = primaryKey.apply(key);
        var byPrimaryKey = idsByPrimaryKey.getInt(pk);
        if (byPrimaryKey >= 0) {
            // Another instance of the same logical resource is already interned. Remember this instance
            // too, so a repeat lookup by identity stays cheap, but hand back the id and the canonical
            // instance the rest of the system already uses.
            ids.put(key, byPrimaryKey);
            return byPrimaryKey;
        }
        var id = keys.size();
        ids.put(key, id);
        idsByPrimaryKey.put(pk, id);
        keys.add(key);
        return id;
    }

    /**
     * @return the id for {@code key} without assigning one, or {@code -1}.
     */
    public int idOf(T key) {
        var id = ids.getInt(key);
        if (id >= 0) {
            return id;
        }
        // Fall back to the primary key: callers look up by whatever instance they happen to hold, and
        // AE2 hands out a fresh one per call. Returning -1 for a resource this interner knows would make
        // every by-key query report zero.
        return idsByPrimaryKey.getInt(primaryKey.apply(key));
    }

    /**
     * @return the key for an id, or {@code null} if the id is unknown.
     */
    @Nullable
    public T keyOf(int id) {
        return id >= 0 && id < keys.size() ? keys.get(id) : null;
    }

    /**
     * @return the number of interned keys, which is also the next id to be assigned.
     */
    public int size() {
        return keys.size();
    }

    public List<T> keys() {
        return keys;
    }

    public void clear() {
        ids.clear();
        idsByPrimaryKey.clear();
        keys.clear();
    }
}
