# UI spacing

The app uses programmatic Android Views. `ui/Tokens.kt` defines the shared `Spacing`
scale. Use its named values for new layouts rather than choosing gaps per screen.

| Relationship | Gap |
| --- | --- |
| Label to its control | `Spacing.SM`, 8dp |
| Related controls or an input and its actions | `Spacing.LG`, 16dp |
| Independent form groups or sections | `Spacing.XL`, 24dp |
| Screen side padding | `Spacing.XL`, 24dp, supplied by `screen()` |
| Dialog content padding | 24dp horizontally, 16dp vertically |

## Forms

`ui/FormLayout.kt` groups each item with its optional label. `addItem` supplies the
label gap, the gap before subsequent groups, full-width controls, and `labelFor`
for accessibility. It replaces any outer layout parameters on the supplied control,
so keep a control's internal styling separate from its position in the form.

```kotlin
val form = FormLayout(app, dialog = true)
val count = makeField(app, "3", "Minimum symbols on a line", InputType.TYPE_CLASS_NUMBER)
form.addItem(count, "Minimum symbols on a line")
form.addItem(targetSpinner, "Apply to")
form.addItem(sampleInput, "Before")
form.addItem(previewOutput, "After")
```

Use `FormLayout(app)` inside an already padded screen body. Wrap dialog forms in
`scroll(form)` so all groups remain reachable on small windows and when the
keyboard is open. After `dialog.show()`, call `dialog.applyFormStyle()` to apply the
theme and request keyboard resizing. Android's default dialog panning can cover
the buttons on a tall form. Keep the dialog's action buttons outside the scrolling form.
The form owns the space between its items. Do not add manual spacers between them.
For controls that belong together, supply a row or column as one item and use
`Spacing.LG` inside that group.

The cleanup preset, custom regex, and quick separator dialogs use this shared
layout. Existing screens adopt it as their forms are revised. The older `Space`
alias remains compatible; new code should use `Spacing`.

## Copy and visual checks

Keep the dialog title and short control labels. Avoid repeating the control's
meaning in a subtitle or helper paragraph. Use inline validation for invalid input.
Add an explanation only when the user needs it to understand a choice or consequence.

After a layout change, cold-start onto the affected screen with `dev_start_screen`,
wait for settled content, and inspect both emulator displays. Open the keyboard,
scroll to the last group, and check that dialog actions stay reachable. Fold and
unfold with the form open to check the live size change. Spacing constants alone
cannot reveal clipping or scrolling problems; this visual check is required by
`android/AGENTS.md`.
