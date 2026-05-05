import { run, logStep } from "../utils";
import { SOURCE_DIRS } from "../constants";
import { CircularDependencyMetrics } from "../types";

export const collectCircularDependencyMetrics = (): CircularDependencyMetrics => {
  logStep("Checking circular dependencies...");

  const result = run("npx", [
    "madge",
    ...SOURCE_DIRS,
    "--extensions",
    "ts,tsx",
    "--ts-config",
    "tsconfig.json",
    "--circular",
    "--json",
    "--no-spinner",
  ]);

  if (result.stderr.trim()) {
    console.warn(result.stderr.trim());
  }

  const output = result.stdout.trim();
  const circularDependencies: string[][] = output ? JSON.parse(output) : [];

  return {
    circularDependencyCount: circularDependencies.length,
    circularDependencies,
  };
};
