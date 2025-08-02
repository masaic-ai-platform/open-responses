
# Developer Playground – Auto‑Generated SDK‑Style Code Snippets
*(uses **postman-collection**, **postman-code-generators**, and Handlebars templates)*

---

## 1 Goals

* Live snippets update with each UI change.
* Generate **OpenAI SDK‑style** code (Python & Node) plus cURL.
* No manual edits when API fields/endpoints evolve.

---

## 2 Dependencies

```bash
pnpm add postman-collection postman-code-generators \
         handlebars react-syntax-highlighter \
         react-copy-to-clipboard
```

| Package | Role |
|---------|------|
| `postman-collection` | Browser‑safe Request object |
| `postman-code-generators` | Built‑in cURL generation |
| `handlebars` | Templates → SDK snippets |
| UI libs | Highlighting & copy button |

---

## 3 Project Skeleton

```
src/
└─ playground/
   ├─ PlaygroundRequest.ts
   ├─ postman.ts
   ├─ snippets/
   │   ├─ generators.ts
   │   └─ templates/
   │       ├─ openai-python.hbs
   │       └─ openai-node.hbs
   └─ CodeTabs.tsx
```

---

## 4 Implementation

### 4.1 `PlaygroundRequest`

```ts
export interface PlaygroundRequest {
  method: 'POST' | 'GET';
  url: '/v1/responses' | '/v1/chat';
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
}
```

### 4.2 Convert to Postman Request

```ts
// playground/postman.ts
import sdk from 'postman-collection';
import { PlaygroundRequest } from './PlaygroundRequest';

export function toPostman(req: PlaygroundRequest) {
  return new sdk.Request({
    url: `https://api.yourai.com${req.url}`,
    method: req.method,
    header: Object.entries(req.headers)
      .map(([key, value]) => ({ key, value })),
    body: req.body && {
      mode: 'raw',
      raw: JSON.stringify(req.body, null, 2),
      options: { raw: { language: 'json' } }
    }
  });
}
```

### 4.3 Generate Snippets

```ts
// playground/snippets/generators.ts
import { convert } from 'postman-code-generators';
import Handlebars from 'handlebars';
import sdk from 'postman-collection';
import pyTplSrc from './templates/openai-python.hbs?raw';
import nodeTplSrc from './templates/openai-node.hbs?raw';

const pyTpl = Handlebars.compile(pyTplSrc);
const nodeTpl = Handlebars.compile(nodeTplSrc);

// helper for pretty JSON
Handlebars.registerHelper('json', (ctx) =>
  JSON.stringify(ctx, null, 2)
);

export function generateSnippets(pmReq: sdk.Request) {
  const bodyObj = JSON.parse(pmReq.body?.raw || '{}');

  // 1. cURL
  const { snippet: curl } = convert(pmReq.toJSON(), {
    language: 'curl',
    variant: 'curl'
  });

  // 2. OpenAI SDK snippets
  const python = pyTpl({ body: bodyObj });
  const node   = nodeTpl({ body: bodyObj });

  return { curl, python, node };
}
```

### 4.4 Handlebars Templates

**openai-python.hbs**

```hbs
from openai import OpenAI
client = OpenAI(api_key="{{OPENAI_API_KEY}}")

response = client.responses.create(
{{#each body as |val key|}}
  {{key}}={{json val}},{{/each}}
)
print(response)
```

**openai-node.hbs**

```hbs
import OpenAI from "openai";
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

const response = await openai.responses.create({
{{#each body as |val key|}}
  {{key}}: {{{json val}}},{{/each}}
});
console.log(response);
```

### 4.5 UI (unchanged)

`CodeTabs.tsx` consumes `{curl, python, node}` and renders language tabs with copy buttons.

---

## 5 Extending to More SDKs

1. Add template `templates/openai-ruby.hbs`.
2. Compile & return in `generateSnippets`.
3. UI auto‑adds new tab.

---

## 6 Quality Gates

| Test | Tool |
|------|------|
| Templates compile | Jest snapshot |
| cURL valid | Child‑process smoke run |
| JSON helper edge cases | Unit tests |

---

### ✅ Deliverable

* `generateSnippets` returns live `{ curl, python, node }`.
* Handlebars templates mirror official OpenAI SDK syntax.
* UI shows up‑to‑date, copy‑paste‑ready code for every request.
