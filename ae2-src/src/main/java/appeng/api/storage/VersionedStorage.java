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

package appeng.api.storage;

/**
 * Implemented by storages that can cheaply report whether their contents changed.
 * <p>
 * The optional native storage index uses this to decide which mounted inventories it has to re-read.
 * Without such a signal the index would have to re-read every cell every tick, which would cost more
 * than it saves.
 * <p>
 * Implementations must bump the version on <strong>every</strong> content mutation, including
 * mutations performed through {@link StorageCell} directly rather than through {@link MEStorage}.
 * Returning a constant is safe but defeats the optimisation for that storage.
 */
public interface VersionedStorage {
    /**
     * @return a counter that changes whenever this storage's contents change. Any monotonic value
     *         works; it is only ever compared for equality with the previously seen value.
     */
    long storageVersion();
}
