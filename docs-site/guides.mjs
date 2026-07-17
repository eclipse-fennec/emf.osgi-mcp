// The published, user-facing docs (allowlist). Shared by the sync script and the
// VitePress config so the set and its order are defined exactly once.
//   file  — source markdown under ../docs (may live in a sub-folder, e.g. manual/)
//   title — sidebar / nav label
//   group — sidebar section the entry belongs to
//
// The route name is derived from the file's base name (see `slugFor`), so the
// numbered manual files keep their natural order (00-…, 01-…, …). Internal docs
// in ../docs (design plans, issue reports) are deliberately NOT listed here and
// stay unpublished (browsed on GitHub).
export const GUIDES = [
  // Guide — orientation.
  { file: 'manual/00-introduction.md', title: 'Introduction', group: 'Guide' },
  { file: 'manual/01-architecture.md', title: 'Architecture', group: 'Guide' },

  // User Manual — how to run, extend and secure the server.
  { file: 'development-guide.md', title: 'Development Guide', group: 'User Manual' },
  { file: 'manual/02-security.md', title: 'Security', group: 'User Manual' },
];

// Route name for a guide: the file's base name without the .md extension.
// e.g. 'manual/01-architecture.md' -> '01-architecture', served at /guides/01-architecture.
export function slugFor(file) {
  return file.replace(/^.*\//, '').replace(/\.md$/, '');
}
