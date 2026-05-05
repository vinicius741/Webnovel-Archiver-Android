import { Mode } from "./types";
import { collectMetrics } from "./collectors";
import { handleCheckMode, handleReportMode, handleBaselineMode } from "./reporters";

const VALID_MODES: Mode[] = ["check", "report", "baseline"];

const parseMode = (arg: string | undefined): Mode => {
  if (arg && VALID_MODES.includes(arg as Mode)) {
    return arg;
  }
  console.error(`Usage: code-quality <check|report|baseline>`);
  console.error(`Invalid mode: ${arg ?? "(none provided)"}`);
  process.exit(2);
};

const main = (): void => {
  const mode = parseMode(process.argv[2]);
  const metrics = collectMetrics();

  switch (mode) {
    case "baseline":
      handleBaselineMode(metrics);
      break;
    case "report":
      handleReportMode(metrics);
      break;
    case "check":
      handleCheckMode(metrics);
      break;
  }
};

main();
