import { useTheme } from "react-native-paper";
import type { AppTheme } from "./types";

export function useAppTheme(): AppTheme {
  return useTheme() as AppTheme;
}
