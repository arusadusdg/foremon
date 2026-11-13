// Runs after every Bash tool call in Claude Code.
// If the command was a gradle build/jar, it tells Claude to run the mod-optimizer subagent.
let input = "";
process.stdin.on("data", (c) => (input += c));
process.stdin.on("end", () => {
  let data;
  try { data = JSON.parse(input); } catch { process.exit(0); }

  const cmd = (data.tool_input && data.tool_input.command) || "";
  const isBuild = /gradlew(\.bat)?\b[^\n]*\b(build|jar|remapJar)\b/.test(cmd);
  if (!isBuild) process.exit(0);

  console.log(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext:
        "A gradle build just ran. Unless you ARE the mod-optimizer subagent, " +
        "use the mod-optimizer subagent now to check build speed, jar size and lag, " +
        "then show the user its short report."
    }
  }));
});
