# Design Anomalies & Inconsistencies

> Keep this log updated.  Resolve each item in follow-up PRs or document why it is acceptable.

| # | Area / File | Description | Proposed Fix |
|---|-------------|-------------|--------------|
| 1 | Duplicate guideline files | `design/design-guidelines.md` (legacy) and `docs/design-guidelines.md` (current) both exist. | Remove or redirect the legacy copy to avoid confusion. |
| 2 | Hard-coded Hex Colors | Several components (e.g. `ChatMessage.tsx`, `ToolExecutionProgress.tsx`) use literal hex colors (`#2e7d32`, `#dc2626`). | Replace with semantic Tailwind tokens (`positive-trend`, `error`). |
| 3 | Inline Styles | Style objects like `style={{ maxHeight: '512px' }}` appear in image tags. | Refactor to Tailwind classes (`max-h-[512px]`). |
| 4 | Mixed Radius Values | Some cards use `rounded-lg` (12 px), others inline `rounded-md` (8 px). | Standardise: `rounded-lg` for cards/modals. |
| 5 | Missing ARIA labels | Icon-only buttons sometimes lack `aria-label`. | Add `aria-label` attr or visually-hidden text. |
| 6 | Shadow Token Drift | Components manually add `shadow-sm` + `hover:shadow-md` without token comment. | Ensure elevation matches guidelines (section 1.5). |
| 7 | Font Fallback Oversight | A few css blocks still reference `SFMono-Regular` only. | Use `font-mono` Tailwind class (Geist Mono fallback chain). |
| 8 | Colour Token Case | Classes like `bg-card` & `bg-muted` are mixed with `bg-muted/50`. | Prefer `bg-muted` + opacity modifier when needed (`bg-muted/50`). |
| 9 | Unused Tokens | `tile-glow-semantic` shadow in Tailwind config not used anywhere. | Remove or implement skeleton loading tile that uses it. |
| 10 | Size Magic Numbers | Some `max-w-[80%]` / `max-h-[512px]` inline sizes. | Promote to logical utilities or container props. |
| 11 | `PlaygroundSidebar.tsx` | Uses `w-[10%] min-w-[160px]`; lacks `hidden md:block` toggle for small screens. | Add `hidden md:flex` and responsive `w-56 md:w-[10%]`. |
| 12 | `ConfigurationPanel.tsx` | Hard-coded `w-[30%]` side-bar; no mobile collapse. | Wrap in Radix `<Drawer>` for `sm` view, use `md:w-[30%]`. |
| 13 | `AiPlayground.tsx` | Chat area `w-[60%]` fixed; breaks on mobile. | Replace with `flex-1` and reorder with CSS grid at `md`+. |
| 14 | `ChatMessage.tsx` | Message container `max-w-[80%]`; on narrow view may overflow. | Use `max-w-full sm:max-w-[80%]`. |
| 15 | `ChatMessage.tsx` | Inline image `max-h-[512px]` fixed. | Convert to `max-h-[60vh]` or `md:max-h-[512px]`. |
| 16 | Several modals (`MCPModal`, `SettingsModal`, etc.) | Hard coded `max-h-[80vh]` / `max-w-2xl`; but not full-screen on mobile. | Add `sm:max-w-full sm:rounded-none sm:h-screen`. |
| 17 | `TextArea` in AiPlayground | `min-h-[130px] max-h-[300px]` may exceed viewport on small phones. | Switch to `min-h-[96px] max-h-[40vh]` and test. |
| 18 | `ToolExecutionProgress.tsx` | Fixed `max-h-[4.5rem]`; fine but consider responsive font scaling. | Use `text-xs md:text-sm`.
| 19 | `ChatMessage.tsx` | Uses `text-green-500` for copy-success checkmarks. | Replace with `text-success` or `text-positive-trend`. |
| 20 | `AiPlayground.tsx` | Copy response-id button uses `text-green-500`. | Same fix as above. |
| 21 | `FileSearchModal.tsx` / `AgenticFileSearchModal.tsx` | Badge success state `bg-green-500`. | Use `bg-success` or `bg-positive-trend`. |
| 22 | Any future CTAs | Ensure no direct `*green-500` utilities—use semantic colours. | Enforce via lint rule or UI review. |

_Last reviewed: 11-July-2025