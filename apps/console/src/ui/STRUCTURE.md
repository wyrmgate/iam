# Console UI module ownership

`src/ui/index.tsx` is the stable Wyrmgate-facing import boundary for shared Console primitives. Feature code should continue importing public primitives from `./ui` (or the equivalent relative barrel) rather than depending on the internal module layout.

Implementation ownership is split so later UI foundation work can proceed with smaller conflict surfaces:

- `layout.tsx` — low-level layout/display exports used by Console composition;
- `forms.tsx` — button, text, select and checkbox controls;
- `page.tsx` — page width, page header, toolbar and surface composition;
- `state.tsx` — alerts, state panels and status chips;
- `table.tsx` — table primitives and the `DataTable` wrapper;
- `dialogs.tsx` — modal, side-panel and confirmation patterns;
- `tabs.tsx` — shared tab navigation primitives;
- `WyrmgateUiProvider.tsx` — theme/provider ownership remains separate from primitive implementation modules.

This decomposition is structural only. Moving a primitive between these implementation modules must not silently change its public props, rendering behavior, theme semantics, authorization assumptions or IAM/API behavior. Changes to a shared public primitive contract still require the explicit coordination/ownership rules defined by issue #312.
