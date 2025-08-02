import { PlaygroundRequest } from '../PlaygroundRequest';

export interface CodeSnippets {
  curl: string;
  python: string;
  node: string;
}

// Helper function to format JSON with proper indentation
function formatJSON(obj: any, indent: number = 2): string {
  return JSON.stringify(obj, null, indent);
}

// Helper function to format JSON for Python/JS with proper indentation
function formatJSONForCode(obj: any, indent: string = '  ', forceInline: boolean = false): string {
  if (obj === null) return 'null';
  if (typeof obj === 'string') {
    // For long strings (like instructions), keep them inline
    if (forceInline || obj.length > 100) {
      return `"${obj.replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`;
    }
    return `"${obj.replace(/"/g, '\\"')}"`;
  }
  if (typeof obj === 'number' || typeof obj === 'boolean') return String(obj);
  
  if (Array.isArray(obj)) {
    if (obj.length === 0) return '[]';
    
    // Special handling for input arrays to keep tools after input
    if (obj.length > 0 && obj[0].role) {
      const items = obj.map(item => `${indent}  ${formatJSONForCode(item, indent + '  ', true)}`);
      return `[\n${items.join(',\n')}\n${indent}]`;
    }
    
    const items = obj.map(item => `${indent}  ${formatJSONForCode(item, indent + '  ')}`);
    return `[\n${items.join(',\n')}\n${indent}]`;
  }
  
  if (typeof obj === 'object') {
    const keys = Object.keys(obj);
    if (keys.length === 0) return '{}';
    
    // Special handling for content arrays with input_text
    if (keys.includes('type') && obj.type === 'input_text') {
      return `{\n${indent}  "type": "input_text",\n${indent}  "text": ${formatJSONForCode(obj.text, indent + '  ', true)}\n${indent}}`;
    }
    
    const items = keys.map(key => {
      const value = obj[key];
      const isStringValue = typeof value === 'string' && (key === 'instructions' || key === 'text');
      return `${indent}  "${key}": ${formatJSONForCode(value, indent + '  ', isStringValue)}`;
    });
    return `{\n${items.join(',\n')}\n${indent}}`;
  }
  
  return String(obj);
}

// Generate cURL command
function generateCurl(request: PlaygroundRequest, baseUrl: string): string {
  // Clean up baseUrl to ensure proper formatting
  const cleanBaseUrl = baseUrl.replace(/\/+$/, ''); // Remove trailing slashes
  const url = `${cleanBaseUrl}${request.url}`;
  let curlCommand = `curl --location '${url}' \\\n`;
  
  // Add headers
  Object.entries(request.headers).forEach(([key, value]) => {
    curlCommand += `  --header '${key}: ${value}' \\\n`;
  });
  
  // Add body if present
  if (request.body) {
    const bodyJson = formatJSON(request.body, 2);
    // Escape single quotes in the JSON for shell
    const escapedJson = bodyJson.replace(/'/g, "'\"'\"'");
    curlCommand += `  --data '${escapedJson}'`;
  }
  
  return curlCommand;
}

// Generate Python OpenAI SDK code
function generatePython(request: PlaygroundRequest): string {
  if (!request.body) {
    return 'from openai import OpenAI\nclient = OpenAI(api_key="YOUR_API_KEY")\n\n# No request body provided';
  }
  
  const bodyObj = request.body as any;
  
  let pythonCode = `from openai import OpenAI
client = OpenAI(api_key="YOUR_API_KEY")

response = client.responses.create(`;

  // Format the body parameters with proper ordering
  const params: string[] = [];
  const orderedKeys = ['model', 'instructions', 'input', 'tools', 'text', 'temperature', 'max_output_tokens', 'top_p', 'store', 'stream'];
  
  orderedKeys.forEach(key => {
    if (bodyObj[key] !== undefined) {
      params.push(`  ${key}=${formatJSONForCode(bodyObj[key], '  ')}`);
    }
  });
  
  // Add any remaining keys not in the ordered list
  Object.entries(bodyObj).forEach(([key, value]) => {
    if (!orderedKeys.includes(key)) {
      params.push(`  ${key}=${formatJSONForCode(value, '  ')}`);
    }
  });
  
  pythonCode += `\n${params.join(',\n')}\n)
print(response)`;
  
  return pythonCode;
}

// Generate Node.js OpenAI SDK code
function generateNode(request: PlaygroundRequest): string {
  if (!request.body) {
    return 'import OpenAI from "openai";\nconst openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });\n\n// No request body provided';
  }
  
  const bodyObj = request.body as any;
  
  let nodeCode = `import OpenAI from "openai";
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

const response = await openai.responses.create({`;

  // Format the body parameters with proper ordering
  const params: string[] = [];
  const orderedKeys = ['model', 'instructions', 'input', 'tools', 'text', 'temperature', 'max_output_tokens', 'top_p', 'store', 'stream'];
  
  orderedKeys.forEach(key => {
    if (bodyObj[key] !== undefined) {
      params.push(`  ${key}: ${formatJSONForCode(bodyObj[key], '  ')}`);
    }
  });
  
  // Add any remaining keys not in the ordered list
  Object.entries(bodyObj).forEach(([key, value]) => {
    if (!orderedKeys.includes(key)) {
      params.push(`  ${key}: ${formatJSONForCode(value, '  ')}`);
    }
  });
  
  nodeCode += `\n${params.join(',\n')}\n});
console.log(response);`;
  
  return nodeCode;
}

export function generateSnippets(request: PlaygroundRequest, baseUrl: string): CodeSnippets {
  try {
    return {
      curl: generateCurl(request, baseUrl),
      python: generatePython(request),
      node: generateNode(request)
    };
  } catch (error) {
    console.error('Error generating snippets:', error);
    return {
      curl: 'Error generating cURL snippet',
      python: 'Error generating Python snippet',
      node: 'Error generating Node.js snippet'
    };
  }
} 