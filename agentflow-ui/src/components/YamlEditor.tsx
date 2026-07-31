import React, { useState, useEffect } from 'react';

interface YamlEditorProps {
  value: string;
  onChange: (value: string) => void;
}

export function YamlEditor({ value, onChange }: YamlEditorProps) {
  const [lines, setLines] = useState<number[]>([]);

  useEffect(() => {
    setLines(Array.from({ length: Math.max(value.split('\n').length, 10) }, (_, i) => i + 1));
  }, [value]);

  return (
    <div className="relative bg-space-900 rounded-lg border border-space-700 overflow-hidden">
      {/* 行号 */}
      <div className="absolute left-0 top-0 bottom-0 w-10 bg-space-900 border-r border-space-700 flex flex-col items-end py-3 px-2 select-none">
        {lines.map(n => (
          <div key={n} className="text-xs text-text-muted leading-5 font-mono">{n}</div>
        ))}
      </div>
      <textarea
        value={value}
        onChange={e => onChange(e.target.value)}
        spellCheck={false}
        className="w-full min-h-[320px] bg-transparent text-text-bright font-mono text-sm leading-5 pl-14 pr-4 py-3 resize-y outline-none placeholder:text-text-muted"
        placeholder={'agentflow:\n  version: "1.0"\n\nnodes:\n  - id: greet\n    agent: greeter\n    prompt_template: "你好 ${name}"'}
      />
    </div>
  );
}
