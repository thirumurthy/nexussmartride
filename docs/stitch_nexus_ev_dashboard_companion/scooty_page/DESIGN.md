---
name: Nexus Cyber-Automotive
colors:
  surface: '#10131a'
  surface-dim: '#10131a'
  surface-bright: '#363940'
  surface-container-lowest: '#0b0e14'
  surface-container-low: '#191c22'
  surface-container: '#1d2026'
  surface-container-high: '#272a31'
  surface-container-highest: '#32353c'
  on-surface: '#e1e2eb'
  on-surface-variant: '#bac9cc'
  inverse-surface: '#e1e2eb'
  inverse-on-surface: '#2e3037'
  outline: '#849396'
  outline-variant: '#3b494c'
  surface-tint: '#00daf3'
  primary: '#c3f5ff'
  on-primary: '#00363d'
  primary-container: '#00e5ff'
  on-primary-container: '#00626e'
  inverse-primary: '#006875'
  secondary: '#ceffdf'
  on-secondary: '#003921'
  secondary-container: '#01f5a0'
  on-secondary-container: '#006b43'
  tertiary: '#ffe9d3'
  on-tertiary: '#472a00'
  tertiary-container: '#ffc681'
  on-tertiary-container: '#7e4e00'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#9cf0ff'
  primary-fixed-dim: '#00daf3'
  on-primary-fixed: '#001f24'
  on-primary-fixed-variant: '#004f58'
  secondary-fixed: '#50ffaf'
  secondary-fixed-dim: '#00e293'
  on-secondary-fixed: '#002111'
  on-secondary-fixed-variant: '#005232'
  tertiary-fixed: '#ffddb8'
  tertiary-fixed-dim: '#ffb95f'
  on-tertiary-fixed: '#2a1700'
  on-tertiary-fixed-variant: '#653e00'
  background: '#10131a'
  on-background: '#e1e2eb'
  surface-variant: '#32353c'
typography:
  display-hero:
    fontFamily: Space Grotesk
    fontSize: 5rem
    fontWeight: '300'
    lineHeight: 5rem
    letterSpacing: -0.04em
  headline-lg:
    fontFamily: Space Grotesk
    fontSize: 2rem
    fontWeight: '700'
    lineHeight: 2.5rem
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Space Grotesk
    fontSize: 1.75rem
    fontWeight: '700'
    lineHeight: 2.25rem
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Space Grotesk
    fontSize: 1.5rem
    fontWeight: '600'
    lineHeight: 2rem
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Space Grotesk
    fontSize: 1.125rem
    fontWeight: '600'
    lineHeight: 1.5rem
    letterSpacing: 0em
  body-lg:
    fontFamily: Manrope
    fontSize: 1rem
    fontWeight: '400'
    lineHeight: 1.5rem
    letterSpacing: 0em
  body-md:
    fontFamily: Manrope
    fontSize: 0.875rem
    fontWeight: '400'
    lineHeight: 1.375rem
    letterSpacing: 0.01em
  body-sm:
    fontFamily: Manrope
    fontSize: 0.75rem
    fontWeight: '500'
    lineHeight: 1.125rem
    letterSpacing: 0.01em
  label-telemetry:
    fontFamily: Space Grotesk
    fontSize: 0.6875rem
    fontWeight: '700'
    lineHeight: 0.875rem
    letterSpacing: 0.14em
  label-chip:
    fontFamily: Manrope
    fontSize: 0.6875rem
    fontWeight: '600'
    lineHeight: 0.875rem
    letterSpacing: 0.05em
  metric-numeral:
    fontFamily: Space Grotesk
    fontSize: 1.5rem
    fontWeight: '600'
    lineHeight: 1.75rem
    letterSpacing: -0.02em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.75rem
  gutter-desktop: 1.25rem
  margin: 1rem
  margin-desktop: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.25rem
  space-xl: 1.75rem
---

## Brand & Style

The design system establishes a high-performance, dark futuristic cyber-automotive aesthetic tailored for smart electric vehicle companions. It communicates surgical precision, instant telemetry feedback, and ecological intelligence. The target audience comprises tech-forward urban riders and mobility enthusiasts who demand immediate, glanceable data under all riding and lighting conditions.

The visual style fuses dark automotive minimalism with targeted cyberpunk glassmorphism. Interfaces leverage ultra-deep obsidian and charcoal substrates, layered with hairline translucent borders and precise luminous neon accents. Emphasizing functional instrumentation, micro-glow badges, and aerodynamic curves, the interface evokes the feeling of pilot instrumentation—engineered, responsive, and ultra-modern.

## Colors

The palette revolves around deep void depths offset by vivid luminescence:
- **Primary (`#00E5FF`):** Electric Cyan serves as the lead brand accent, primary status indicator, interactive focus state, and active navigational telemetry.
- **Secondary (`#00F5A0`):** Kinetic Emerald Green signifies regenerative braking, optimal energy efficiency, high battery levels, eco scores, and positive cloud sync status.
- **Tertiary (`#F59E0B`):** Warning Amber provides alert telemetry for low battery, service notifications, and critical threshold warnings.
- **Neutral (`#0B0E14`):** Obsidian Black anchors the canvas, paired with `#121824` for card containers, `#1B2433` for nested metric surfaces, and `#1E293B` for structural hairline borders (`rgba(255, 255, 255, 0.08)` for translucent glass borders). High-contrast `#F8FAFC` and muted `#94A3B8` manage textual legibility.

## Typography

The type system blends the aerodynamic, technical geometry of **Space Grotesk** with the balanced legibility of **Manrope**. Space Grotesk commands display statistics, speed numerals, telemetry titles, and monospaced-style technical subheadings (often styled in uppercase with expanded tracking). Manrope drives body paragraphs, context guidance, and operational prompts to guarantee instant comprehension during movement.

Key typographic rules:
- Uppercase headers and subheadings use `label-telemetry` with wide character spacing (0.12em–0.16em) to evoke precision aerospace telemetry.
- High-contrast numerical readouts utilize tight tracking (`-0.02em` to `-0.04em`) to maintain structural density.
- Unit signs (e.g., `KM/H`, `km`, `L`) sit adjacent to metric numerals with reduced visual weight and muted color.

## Layout & Spacing

The layout applies a vertically continuous fluid grid model. On mobile viewport widths, content follows a single-column or 2-column stacked metric structure with compact 12px (`0.75rem`) gutters and 16px (`1rem`) lateral page margins to maximize data real estate while keeping interactions within thumb reach. Desktop and tablet dashboard layouts stretch to 12 columns with 20px gutters and 32px margins.

Spacing enforces rhythmic separation between distinct vehicle modules:
- Outer card margins and inter-card vertical gaps use `space-md` (16px) to maintain modular isolation.
- Internal card padding employs `space-lg` (20px) to give critical numbers visual cushion.
- Dense telemetry pairs use `space-xs` (4px) and `space-sm` (8px) for title-value correlation.

## Elevation & Depth

Visual hierarchy uses tonal surface stacking combined with targeted neon luminescence and glassmorphic depth:

1. **Substrate (Level 0):** Pure dark canvas (`#0B0E14`) anchoring the screen.
2. **Primary Panels (Level 1):** Solid `#121824` with 80% opacity, 16px backdrop-filter blur, enclosed by a 1px border of `rgba(255, 255, 255, 0.08)`.
3. **Nested Modules (Level 2):** Inset containers and interactive sub-cards sit at `#1A2232` with a 1px border of `rgba(255, 255, 255, 0.04)`.
4. **Micro-Glows & Neon Elevation:** Interactive states, primary progress fills, and active states don't rely on drop shadows; instead, they employ soft, diffuse colored blurs:
   - Primary Cyan: `box-shadow: 0 0 16px -2px rgba(0, 229, 255, 0.35)`
   - Energy Green: `box-shadow: 0 0 16px -2px rgba(0, 245, 160, 0.35)`
   - Subtle Card Edge: No heavy black drop shadows; crisp borders retain structural form in pitch-black environments.

## Shapes

The geometric identity balances industrial tech borders with ergonomic automotive roundedness (Level 2):
- **Base Cards & Panels:** Enclosed in `rounded-xl` (1.5rem / 24px) corners, echoing automotive instrument pods and scooter fairings.
- **Nested Metrics & Inner Panels:** Bound by `rounded-lg` (1rem / 16px) corners.
- **Micro-Badges, Chips, & Segmented Controllers:** Formed into complete pills (`9999px` radius) to signify touch target comfort and instantaneous state changes.
- **Track Indicators & Sliders:** Pill-capped geometries with embedded glowing linear fills.

## Components

### Buttons & CTAs
- **Primary Hero Button:** Rounded pill (`rounded-full`), height `52px`, styled with electric cyan gradient fill or high-visibility electric blue (`#0066FF` to `#00E5FF`), bold uppercase label, accompanied by glowing ambient cyan aura on interaction.
- **Outlined / Secondary Button:** Glass backdrop (`rgba(255, 255, 255, 0.03)`), 1.5px solid border in `#00E5FF`, uppercase technical tracking with an active cyan glow.
- **Icon / Utility Actions:** Squircle or pill containers (`#1A2232`), 48px target, displaying neon monochrome icon vectors (e.g., service wrench in `#00F5A0`, trash/danger in `#EF4444`).

### Chips & Micro-Glow Badges
- **Status Pills:** Pill-shaped status indicators (`padding: 4px 10px`) with 1px border.
  - Active/Online: Border `#10B981`, background `rgba(16, 185, 129, 0.12)`, text `#10B981`. Includes a pulsing 6px neon micro-dot.
  - Warning/Connecting: Border `#F59E0B`, background `rgba(245, 158, 11, 0.12)`, text `#F59E0B`.

### Segmented Controls
- **Pill Track:** Unified container (`#121824`) with a 1px border in `rgba(255, 255, 255, 0.08)`, height `40px`, padded with 4px internal offset.
- **Segment State:** Inactive tabs are flat with `#94A3B8` text. The active segment features an elevated `#1E293B` or `#1B3B36` surface, enclosed by a 1px `#00F5A0` or `#00E5FF` hairline, with vibrant white/green typography.

### Progress & Telemetry Bars
- **Energy Level Track:** 8px height, dark charcoal slot (`#263040`) with full rounded ends.
- **Live Charge Fill:** Dynamic dual-stop neon gradient (`#00E5FF` to `#00F5A0`) with an animated right-edge glow pulse.

### Telemetry & Vehicle Cards
- **Instrumentation Frame:** Heavy `#121824` container with 24px corner radius and hairline glass border.
- **Header:** Uppercase cyan tracking (`Space Grotesk`, `label-telemetry`), balanced with right-aligned muted sync indicators.
- **Data Sub-Cards:** Two-column grid (`#1A2232`) displaying green/cyan metric values followed by secondary insight captions.

### Inputs & Controls
- **Ghost Input Fields:** Dark transparent inputs (`#0E131D`) with subtle placeholder text, transitioning to a luminous 1.5px `#00E5FF` stroke upon focus.
- **Telemetry Checkboxes & Toggles:** High-contrast switch with a neon pill head, toggling between muted slate (`#334155`) and neon emerald (`#00F5A0`).