/**
 * Post-process Milkdown's serialized markdown output to remove artifacts
 * that would corrupt Obsidian notes:
 *
 * 1. <br /> lines: Milkdown emits these for empty paragraphs (remarkPreserveEmptyLine
 *    plugin). Obsidian doesn't understand the convention; a blank line is enough.
 *
 * 2. \# at line start: mdast-util-to-markdown escapes '#' at atBreak positions to
 *    prevent accidental ATX headings. But #hashtag (no space after #) is valid
 *    Obsidian tag syntax, not a heading. Un-escape those.
 */
export function cleanMilkdownOutput(md) {
  // Strip lines that are only a <br> variant (empty-paragraph markers)
  let out = md.replace(/^<br\s*\/?>$/gm, '');

  // Un-escape \#tag at line start when not followed by space (i.e. it's a tag, not \# Heading)
  out = out.replace(/^\\#(?=[^ \t\n])/gm, '#');

  return out;
}
