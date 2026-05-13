import { SegmentedButtons } from "react-native-paper";
import type { ComponentProps } from "react";
import { useAppTheme } from "../../theme/useAppTheme";

type SegmentedButtonsProps<T extends string = string> = ComponentProps<
  typeof SegmentedButtons<T>
>;

export function AppSegmentedButtons<T extends string = string>({
  style,
  ...rest
}: SegmentedButtonsProps<T>) {
  const theme = useAppTheme();

  return (
    <SegmentedButtons<T>
      theme={{ roundness: theme.shapes.chipRadius / 5 }}
      style={style}
      {...rest}
    />
  );
}
