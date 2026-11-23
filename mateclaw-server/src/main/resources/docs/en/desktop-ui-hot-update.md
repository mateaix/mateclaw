# Desktop UI hot-update design

This is a design document, not a statement that UI-only updates are available in every released desktop package.

The desktop currently starts its bundled Java backend and opens the locally served UI. The proposed UI-only update path would keep the Electron/JRE/JAR update mechanism, verify compatible frontend assets separately, and fall back to the bundled UI after failure. Backend API compatibility and rollback must be validated before activation.

See the [full Chinese design](../zh/desktop-ui-hot-update) for scope and constraints, and [Desktop](./desktop) for supported installation and update instructions.
