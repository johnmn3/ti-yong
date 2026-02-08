# Changelog

## Unreleased

### Fixed

- **Function application arity-10 bug**: Fixed an issue where the 10th argument
  was silently dropped when invoking a transformer with 10 or more positional
  arguments. This was caused by a bug in the underlying `wrap-map` library
  (0.1.11) and is resolved by upgrading to `wrap-map` 0.1.12.

- **Spec validation for inherited data**: `::spec` tf-pre now runs after
  `::with` tf-pre, ensuring that specs inherited from mixins are present in the
  environment during validation. Previously, `::spec` ran first and could not
  see specs contributed by `::with` mixins.

- **Dissoc protection for mixin-inherited keys**: Calling `dissoc` on a key
  that was contributed by a mixin (via `:with`) now correctly prevents that key
  from being re-merged during invocation. The transformer tracks explicitly
  removed keys and excludes them after mixin data is merged, so spec validation
  correctly catches the missing key. Re-adding the key via `assoc` clears the
  exclusion.

### Changed

- **Migrated from dyna-map to wrap-map**: The internal map implementation has
  been replaced with [`com.jolygon/wrap-map`](https://github.com/jolygon/wrap-map),
  a library for creating maps with customizable behavior handlers. This is an
  internal implementation change; the transformer API remains the same.

- **Simplified preform**: Removed the `:instantiated?` and `:init-set` tracking
  mechanisms from `preform`. These were artifacts of the previous `dyna-map`
  implementation and are unnecessary with `wrap-map`'s lazy invocation model,
  where `preform` runs at invocation time rather than during construction.
