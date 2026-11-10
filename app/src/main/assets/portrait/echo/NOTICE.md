# Echo portrait data

Source: https://github.com/SugarChain-Studio/echo-clothing-ext
Revision: `4478e33044a6d87a4d1b0c6ed8d38a549e1d4204`

Copyright Echo and the contributors named in LICENSE.txt. The extracted definitions
and derived catalog are distributed under CC BY-NC-SA 4.0:
https://creativecommons.org/licenses/by-nc-sa/4.0/
See LICENSE.txt and LICENSE-APPENDIX.md for upstream attribution and exceptions.

mobile-bc modifications: selected static definitions and pure pose/mask/type construction
are extracted into JSON. Game hooks, interactions, account handling, animations,
and components needing unsupported runtime helpers are excluded. Inclusion follows
portrait compatibility; upstream asset-specific terms remain in LICENSE-APPENDIX.md.
Images are fetched on demand from the pinned upstream revision and verified against
their Git blob identities. Upstream image files are not bundled; the APK includes a locally generated rectangular mask.

Regenerate with `scripts/generate-echo-portrait.mjs`. The script checks source files
against the pinned Git tree and BC constants against the R133 script manifest.
The resulting catalog is a partial compatibility snapshot, not the full Echo mod.

Reviewed BeforeDraw and AfterDraw callbacks are replaced with identifiers for the
packaged portrait/supplement adapters, including text and masked drawing. The PartsMask
and GLImageRenderer helpers are derived from src/lib/draw at the same pinned revision. Their source identities and ported-code
attribution are maintained in that package; other runtime callbacks remain excluded.
The white dress is credited upstream as originally designed by 01musume.
The evening gown adapter preserves its suspended kneeling pose override.

Typed left/right/both helpers and modular configurations are extracted as data.
The snapshot includes the weapon prop's type/sheathe modules and independent
tattoo layers. Inherited BC image groups are used when checking image ownership.
These definitions support rendering received TypeRecord values; they do not
enable option editing, activities or other gameplay callbacks in the preview.

The snapshot also retains six invisible ItemMisc markers from the pinned
interaction helpers (贴贴, 扛起来的麻袋, 抓住). Their definitions are copied as data;
their validation, leash and gameplay hooks are not installed.
