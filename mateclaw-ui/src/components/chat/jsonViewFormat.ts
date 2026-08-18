export function prettyPrintJsonForDisplay(raw: string): string {
  // Validate JSON, but do not use the parsed value for rendering: JSON.parse
  // coerces 19-digit Snowflake IDs through JS Number and changes their text.
  JSON.parse(raw)
  return prettyPrintJsonLexically(raw)
}

function prettyPrintJsonLexically(raw: string): string {
  let out = ''
  let indent = 0
  let inString = false
  let escaped = false
  const pad = () => '  '.repeat(indent)

  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i]

    if (inString) {
      out += ch
      if (escaped) {
        escaped = false
      } else if (ch === '\\') {
        escaped = true
      } else if (ch === '"') {
        inString = false
      }
      continue
    }

    if (/\s/.test(ch)) continue

    if (ch === '"') {
      inString = true
      out += ch
    } else if (ch === '{' || ch === '[') {
      out += ch
      indent++
      out += '\n' + pad()
    } else if (ch === '}' || ch === ']') {
      indent = Math.max(0, indent - 1)
      out = out.replace(/[ \t]*$/, '')
      out += '\n' + pad() + ch
    } else if (ch === ',') {
      out += ch + '\n' + pad()
    } else if (ch === ':') {
      out += ': '
    } else {
      out += ch
    }
  }

  return out
}
