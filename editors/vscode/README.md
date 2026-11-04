# Oqthara for VS Code

Syntax highlighting, snippets and bracket/indent rules for `.oq` files.

## Install from source

```bash
cd editors/vscode
npx --yes @vscode/vsce package
code --install-extension oqthara-0.1.0.vsix
```

## What you get

- Comments (`#`), single, double and triple-quoted strings.
- Interpolation highlighting inside `{...}` in string literals.
- Control keywords, language constants and word operators (`and`, `or`, `not`).
- Function and type names after `fun` and `data`, and call sites.
- Snippets: `hello`, `fun`, `data`, `for`, `forr`, `while`, `if`, `ife`, `try`, `spawn`,
  `use`, `usejava`, `list`, `map`, `lam`.
- Auto-indent on block openers and dedent on `end`, `else`, `catch`.

## Keeping it in sync

`EditorSupportTest` in `oq-cli` fails if:

- a keyword the lexer knows is not highlighted;
- a snippet does not parse as valid Oqthara;
- `package.json` references a file that does not exist;
- an example in `examples/` stops parsing.

That means the extension cannot silently drift from the language.
