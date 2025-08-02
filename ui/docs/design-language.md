# Masaic Design Language

> This document consolidates _all_ foundational style rules, tokens, patterns, and UX conventions present in the code-base (Tailwind config, UI components, and existing guidelines).  Follow this checklist **before** merging any new UI work.

---

## 1. Foundations

### 1.1 Typography
| Purpose | Font Family | Default Size |
|---------|-------------|--------------|
| Headings / Body | `Geist Sans`, fallback `Inter, sans-serif` | Tailwind defaults (`text-base`, etc.) |
| Code / Mono | `Geist Mono`, fallback `Menlo, Consolas, monospace` | In code blocks we lock to `13 px` / `1.5` line-height |

Additional Tailwind token overrides live in `tailwind.config.ts → theme.extend.fontFamily` and `fontSize.btn-*`.

### 1.2 Spacing / Sizing
* **4 px baseline grid** – All margin / padding values multiply this (Tailwind scale 1 → 4 px, 2 → 8 px …).
* **Container** – Centered with `padding: 2rem` up to a `2xl` = 1400 px breakpoint.

### 1.3 Color System  
All colour utilities map to CSS custom-properties in _dark + light_ themes and follow a Geist-inspired palette.

Semantic aliases (always prefer these over raw hex):
* `bg-background`, `text-foreground`
* `bg-muted`, `bg-card`, `bg-popover`
* State: `positive-trend`, `negative-trend`, `critical-alert`, `warning`, `success`, `error`
* Brand greys: `accentGray-1 … accentGray-8`
* Accent brand primaries: `primary`, `secondary`

> Never hard-code hex values in components – import the token or use the Tailwind class.

### 1.4 Border & Radius
* Default radius =`6 px` (`rounded`)  
  * Buttons: `rounded-md` (6–8 px)  
  * Cards/Modals: `rounded-lg` (12 px)  
* Border colour defaults to `border-border` token.

### 1.5 Shadows (Elevation)
| Utility | Usage |
|---------|-------|
| `shadow-xs` | Hairline tooltip / focus ring |
| `shadow-sm` | Subtle hover-lift |
| `shadow-md` | Standard card |
| `shadow-lg` | Popover / dropdown |
| `shadow-xl` | Modal / overlay |

### 1.6 Responsive / Adaptive Design
The entire application is **mobile-first** and adapts up to desktop-wide breakpoints.  Use Tailwind’s built-in media prefixes exclusively (`sm:`, `md:`, `lg:`, `xl:`, `2xl:`) – do **not** write raw media queries.

| Token | Min-Width | Typical Usage |
|-------|----------|---------------|
| `sm:` | `640 px` | Collapse single-column → multi-column, show inline buttons |
| `md:` | `768 px` | Enable persistent sidebar (`PlaygroundSidebar`) |
| `lg:` | `1024 px` | Split-pane layouts (`ConfigurationPanel` + chat area) |
| `xl:` | `1280 px` | Wider container paddings / larger gutters |
| `2xl:` | `1536 px` | Large desktop / ultra-wide only tweaks |

Guidelines:
* **Flex & Grid** – always add `min-w-0` to flex children that should shrink, preventing overflow on small screens.
* **Percent widths** – side panels use fixed `w-[30%] lg:w-[30%]` on desktop, but switch to 100 % width + accordion when `md` breakpoint is not met.
* **Scroll Areas** – wrap long pane bodies with `overflow-y-auto` + `max-h-screen` so mobile doesn’t stretch.
* **Modals** – on mobile they open full-screen (`sm:max-w-full sm:rounded-none`); on desktop keep `rounded-lg` & fixed max-width tokens.
* **Tables / Code blocks** – always add `overflow-auto`, `whitespace-pre` or `break-all` so lines don’t break the viewport.
* **Utility shortcuts**  
  * Center blocks: `mx-auto max-w-[90%] sm:max-w-[80%]`  
  * Hide text on mobile: `hidden md:inline`  
  * Mobile drawer pattern: use Radix `<Drawer>` or Vaul `<Sheet>` APIs.
* **Hook** – `src/hooks/use-mobile.tsx` returns `{ isMobile }` (viewport ≤ 768 px).  Use for behaviour changes / API optimisation, _not_ for styling.

> When in doubt: build for **320 px** wide first, then layer progressive enhancements.

---

## 2. Component Conventions

### 2.1 Buttons
* Use existing variants from `ui/button.tsx` (`default`, `ghost`, `outline`, `destructive`).
* Size tokens: `btn-lg`, `btn-md`, `btn-sm`.
* Include an `aria-label` when the button only shows an icon.

### 2.2 Form Controls
* Base components under `ui/*` already integrate `@tailwindcss/forms` reset.
* Always wrap with `<Label>` and pass `id` for accessibility.

### 2.3 Modals / Dialogs
* Backdrop: `bg-background/80` + `backdrop-blur-sm`.
* Card container: `border border-border` + `shadow-xl` + `rounded-lg`.
* Max-width breakpoints: `max-w-md` (forms), `max-w-3xl` (content), `max-w-4xl` (code).

### 2.4 Code Blocks
* Use **react-syntax-highlighter** with `atomOneDark` theme.
* Props:
  * `showLineNumbers`
  * Custom style – `background: transparent`, `padding:16px`, `fontSize:13px`, `fontFamily:"Geist Mono"`.
* Wrap in `bg-gray-900 rounded-lg overflow-auto` for dark canvas.

### 2.5 Icons
* `lucide-react` icons at `w-4 h-4` (small) or `w-5 h-5` (default).

### 2.6 Animation
* Prefer utility classes from `tailwindcss-animate` (`animate-pulse`, etc.).
* Accordion open/close keyframes provided in Tailwind config.

---

## 3. UX & Interaction
1. **Dark-mode first** – Components must render correctly in both themes.
2. **Keyboard navigation** – Ensure focus rings (`focus:outline-none focus:ring-2`) and `tabIndex` on custom elements.
3. **Motion** – Keep durations ≤ 200 ms (`duration-150/200`).
4. **Copy-to-clipboard** pattern:
   * Absolute positioned button inside `relative` parent.
   * Success state toggles icon (`Check`) for 2 s.

---

## 4. File & Naming Standards
* Component files: `PascalCase.tsx` under `src/components/...`.
* UI primitives live in `src/components/ui/`.
* Hooks in `src/hooks/`. Shared utilities in `src/lib/`.
* Avoid default exports **except** for components; named exports for utils.

---

## 5. Accessibility Checklist (WCAG AA)
- [ ] Contrast ≥ 4.5:1 for text (`text-muted-foreground` is safe on `bg-background`).
- [ ] Provide `alt` text for `<img>` tags.
- [ ] Use semantic HTML (`<button>`, `<nav>`, `<header>`).
- [ ] Ensure modals trap focus (Radix UI Dialog handles this for us).
- [ ] Keyboard & screen-reader labels for interactive SVG icons.

---

## 6. Contribution Workflow
1. **Design proposal** → update this doc if you introduce new tokens.
2. **Storybook / local preview** – validate light & dark.
3. **PR checklist** includes running `npm run lint` and visual QA.

---

_Updated: {TODAY}_ 