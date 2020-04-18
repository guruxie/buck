/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.cell;

import com.facebook.buck.core.cell.exception.UnknownCellException;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.CellRelativePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

/** Contains base logic for {@link CellPathResolver}. */
public abstract class AbstractCellPathResolver implements CellPathResolver {

  /** @return sorted set of known roots in reverse natural order */
  @Override
  public ImmutableSortedSet<AbsPath> getKnownRoots() {
    return ImmutableSortedSet.orderedBy(AbsPath.comparator().reversed())
        .addAll(
            getCellPathsByRootCellExternalName().values().stream()
                .collect(ImmutableList.toImmutableList()))
        .add(getCellPathOrThrow(Optional.empty()))
        .build();
  }

  @Override
  public AbsPath getCellPathOrThrow(Optional<String> cellName) {
    return getCellPath(cellName)
        .orElseThrow(
            () ->
                new UnknownCellException(cellName, getCellPathsByRootCellExternalName().keySet()));
  }

  @Override
  public AbsPath getCellPathOrThrow(CanonicalCellName cellName) {
    return getNewCellPathResolver().getCellPath(cellName);
  }

  @Override
  public AbsPath resolveCellRelativePath(CellRelativePath cellRelativePath) {
    AbsPath cellPath = getNewCellPathResolver().getCellPath(cellRelativePath.getCellName());
    return cellPath.resolve(cellRelativePath.getPath().toPath(cellPath.getFileSystem()));
  }
}
