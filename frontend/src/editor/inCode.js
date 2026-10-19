// True when `pos` sits inside fenced / indented / inline code (per the lezer markdown tree).
import { syntaxTree } from '@codemirror/language';

export function inCode(state, pos) {
  for (let n = syntaxTree(state).resolveInner(pos, 1); n; n = n.parent) {
    if (/Code/.test(n.name)) return true;
  }
  return false;
}
