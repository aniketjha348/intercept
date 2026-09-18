---
name: Ui design Guide
description: Directives and best practices for creating beautiful, modern, and highly polished web interfaces.
---

# UI Design Guide

When the user asks you to generate something related to design, build a website, or perform a UI overhaul, you MUST apply these modern UI/UX principles to ensure the result is professional, beautiful, and highly polished. Do not settle for default browser styles.

## 1. Typography
- **Fonts**: Use modern, clean sans-serif fonts (e.g., Inter, Roboto, San Francisco, or system-ui).
- **Hierarchy**: Clearly distinguish between headings (H1, H2) and body text using font weights (e.g., bold headers, regular body) and sizes.
- **Readability**: Keep body text line-height around `1.5` to `1.6`. Use slightly muted colors for body text (e.g., `#4b5563` on light themes or `#a1a1aa` on dark themes) rather than stark black or pure white.

## 2. Layout & Whitespace (Negative Space)
- **Breathing Room**: Be generous with padding and margins. Do not cram elements together.
- **Alignment**: Use CSS Flexbox or Grid for perfect alignment. Center things deliberately.
- **Constraints**: Limit the maximum width of text containers (e.g., `max-w-3xl`) so lines don't stretch too far across wide screens.

## 3. Colors & Theme
- **Backgrounds**: Use soft, off-white (e.g., `#f9fafb`) or rich dark (e.g., `#0f172a`) background colors instead of pure `#ffffff` or `#000000`.
- **Accents**: Pick a primary brand color (e.g., a vibrant blue or purple) and use it sparingly for primary buttons, active states, and important links.
- **Gradients & Glassmorphism**: Where appropriate, use subtle background gradients or translucent frosted-glass effects (e.g., `backdrop-filter: blur(10px); background: rgba(255, 255, 255, 0.1);`).

## 4. Components & Shapes
- **Corners**: Use consistent `border-radius` (e.g., `8px` or `12px` for cards/buttons, full pill-shapes for badges).
- **Shadows**: Add soft, diffuse box-shadows to cards and dropdowns to create depth and elevation. Avoid harsh, dark shadows (e.g., use `box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1)`).
- **Borders**: If using borders, make them very subtle (e.g., `1px solid rgba(255,255,255,0.1)` or `#e5e7eb`).

## 5. Micro-Interactions & States
- **Transitions**: Apply `transition: all 0.2s ease;` to buttons, links, and interactive elements.
- **Hover/Active**: Always provide visual feedback when an element is hovered (e.g., slightly lighten/darken the background, or lift the element up by 2px) or clicked.
- **Focus**: Remove default browser outlines and replace them with custom, aesthetic focus rings.

## 6. Responsiveness
- Ensure all designs are mobile-friendly first.
- Use responsive units or media queries to collapse grids into single columns on smaller screens.
- Ensure tap targets (buttons, links) are at least `44px` tall on mobile devices.

## Directives
- **Never use raw unstyled HTML** for a final output unless explicitly requested.
- **Use modern CSS frameworks** (like Tailwind CSS) if permitted by the project, or write clean, well-structured vanilla CSS using CSS Variables for theming.
- **Review your design**: Before finalizing the code, ask yourself: "Does this look like a premium modern SaaS product or a 1990s web page?" Adjust accordingly.
