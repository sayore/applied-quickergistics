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

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

/**
 * Assigns dense {@code int} ids to identity-distinct keys.
 * <p>
 * AE2 keys such as {@code AEItemKey} are canonical per item type, i.e. two lookups of the same item return the same
 * instance, so identity semantics are both correct and far cheaper than {@code equals}/{@code hashCode}. Ids start at 0
 * and grow monotonically, which keeps them usable as array indices in the native index and makes cell slices naturally
 * close to sorted.
 */
public final class DenseKeyInterner<T> {
    private final Reference2IntMap<T> ids = new Reference2IntOpenHashMap<>();
    private final List<T> keys = new ArrayList<>();

    public DenseKeyInterner() {
        ids.defaultReturnValue(-1);
    }

    /**
     * @return the id for {@code key}, assigning a new one if needed.
     */
    public int intern(T key) {
        var id = ids.getInt(key);
        if (id >= 0) {
            return id;
        }
        id = keys.size();
        ids.put(key, id);
        keys.add(key);
        return id;
    }

    /**
     * @return the id for {@code key} without assigning one, or {@code -1}.
     */
    public int idOf(T key) {
        return ids.getInt(key);
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
        keys.clear();
    }
}
