import { Button } from "react-native-paper";
import type { ComponentProps } from "react";
import { useAppTheme } from "../../theme/useAppTheme";

type PaperButtonProps = ComponentProps<typeof Button>;

export function AppButton({
  mode,
  style,
  contentStyle,
  uppercase,
  ...rest
}: PaperButtonProps) {
  const theme = useAppTheme();
  const defaultMode = theme.buttonDefaults.mode;
  const resolvedMode = mode ?? defaultMode;
  const resolvedUppercase =
    uppercase ?? theme.buttonDefaults.textTransform === "uppercase";

  return (
    <Button
      mode={resolvedMode}
      uppercase={resolvedUppercase}
      style={[
        {
          borderRadius: theme.shapes.buttonRadius,
          minHeight: theme.buttonDefaults.buttonHeight,
          ...(resolvedMode === "outlined" && {
            borderWidth: theme.buttonDefaults.borderWidth,
          }),
        },
        style,
      ]}
      contentStyle={[
        { minHeight: theme.buttonDefaults.buttonHeight - 8 },
        contentStyle,
      ]}
      {...rest}
    />
  );
}
