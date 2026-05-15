import React, { useState } from "react";
import { StyleSheet } from "react-native";
import {
  Text,
  useTheme,
  Modal,
  TextInput,
  Divider,
  Menu,
} from "react-native-paper";
import { AppButton } from "../theme/AppButton";
import { useAppTheme } from "../../theme/useAppTheme";
import { useScreenLayout } from "../../hooks/useScreenLayout";

interface Props {
  visible: boolean;
  onDismiss: () => void;
  concurrency: string;
  concurrencyError: string | undefined;
  delay: string;
  delayError: string | undefined;
  onConcurrencyChange: (text: string) => void;
  onDelayChange: (text: string) => void;
  onConcurrencyBlur: () => void;
  onDelayBlur: () => void;
  onClearFinishedPress: () => void;
  selectedSource: string | null;
  onSourceSelect: (source: string | null) => void;
  availableProviders: string[];
  onResetSource: () => void;
}

type ScreenLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
  heightClass?: "compact" | "medium" | "expanded";
  isCompactHeight?: boolean;
};

export const DownloadManagerSettingsModal: React.FC<Props> = ({
  visible,
  onDismiss,
  concurrency,
  concurrencyError,
  delay,
  delayError,
  onConcurrencyChange,
  onDelayChange,
  onConcurrencyBlur,
  onDelayBlur,
  onClearFinishedPress,
  selectedSource,
  onSourceSelect,
  availableProviders,
  onResetSource,
}) => {
  const theme = useTheme();
  const appTheme = useAppTheme();
  const layout = useScreenLayout() as ScreenLayout;
  const [menuVisible, setMenuVisible] = useState(false);
  const screenWidth = layout.screenWidth || 0;
  const widthClass =
    layout.widthClass ||
    (screenWidth >= 960
      ? "expanded"
      : screenWidth >= 600
        ? "medium"
        : "compact");
  const modalMargin = widthClass === "expanded" ? 32 : 20;
  const modalMaxWidth = widthClass === "expanded" ? 560 : 480;
  const modalMaxHeight =
    layout.isCompactHeight || layout.heightClass === "compact" ? "92%" : "80%";

  const sourceLabel = selectedSource ?? "Global (default)";

  return (
    <Modal
      visible={visible}
      onDismiss={onDismiss}
      contentContainerStyle={[
        styles.modalContent,
        {
          borderRadius: appTheme.shapes.dialogRadius,
          backgroundColor: theme.colors.surface,
          margin: modalMargin,
          maxWidth: modalMaxWidth,
          maxHeight: modalMaxHeight,
        },
      ]}
    >
      <Text variant="headlineSmall" style={styles.modalTitle}>
        Download Manager
      </Text>

      <AppButton
        mode="outlined"
        icon="delete-sweep"
        onPress={onClearFinishedPress}
        textColor={theme.colors.error}
        style={styles.clearButton}
      >
        Clear Finished Downloads
      </AppButton>

      <Divider style={styles.divider} />

      <Text variant="labelLarge" style={styles.sectionLabel}>
        Source Override
      </Text>
      <Menu
        visible={menuVisible}
        onDismiss={() => setMenuVisible(false)}
        anchor={
          <AppButton
            mode="outlined"
            onPress={() => setMenuVisible(true)}
            icon="chevron-down"
            style={styles.sourceButton}
            contentStyle={styles.sourceButtonContent}
          >
            {sourceLabel}
          </AppButton>
        }
      >
        <Menu.Item
          onPress={() => {
            onSourceSelect(null);
            setMenuVisible(false);
          }}
          title="Global (default)"
          leadingIcon={
            selectedSource === null ? "check" : undefined
          }
        />
        {availableProviders.map((name) => (
          <Menu.Item
            key={name}
            onPress={() => {
              onSourceSelect(name);
              setMenuVisible(false);
            }}
            title={name}
            leadingIcon={
              selectedSource === name ? "check" : undefined
            }
          />
        ))}
      </Menu>

      {selectedSource && (
        <AppButton
          mode="text"
          icon="restore"
          onPress={onResetSource}
          compact
          textColor={theme.colors.onSurfaceVariant}
          style={styles.resetButton}
        >
          Reset to Default
        </AppButton>
      )}

      <Divider style={styles.divider} />

      <TextInput
        label="Simultaneous Downloads"
        value={concurrency}
        onChangeText={onConcurrencyChange}
        onEndEditing={onConcurrencyBlur}
        keyboardType="number-pad"
        mode="outlined"
        style={styles.input}
        error={!!concurrencyError}
        right={<TextInput.Affix text="files" />}
      />
      {concurrencyError ? (
        <Text
          variant="bodySmall"
          style={[styles.error, { color: theme.colors.error }]}
        >
          {concurrencyError}
        </Text>
      ) : null}

      <TextInput
        label="Delay Between Downloads"
        value={delay}
        onChangeText={onDelayChange}
        onEndEditing={onDelayBlur}
        keyboardType="number-pad"
        mode="outlined"
        style={styles.input}
        error={!!delayError}
        right={<TextInput.Affix text="ms" />}
      />
      {delayError ? (
        <Text
          variant="bodySmall"
          style={[styles.error, { color: theme.colors.error }]}
        >
          {delayError}
        </Text>
      ) : null}

      <AppButton onPress={onDismiss} style={styles.closeButton}>
        Done
      </AppButton>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalContent: {
    width: "100%",
    alignSelf: "center",
    padding: 20,
  },
  modalTitle: {
    marginBottom: 16,
  },
  clearButton: {
    marginBottom: 8,
  },
  divider: {
    marginVertical: 12,
  },
  sectionLabel: {
    marginBottom: 8,
  },
  sourceButton: {
    marginBottom: 8,
  },
  sourceButtonContent: {
    flexDirection: "row-reverse",
  },
  resetButton: {
    marginBottom: 4,
    alignSelf: "flex-start",
  },
  input: {
    marginBottom: 12,
  },
  error: {
    marginTop: -8,
    marginBottom: 8,
  },
  closeButton: {
    marginTop: 16,
  },
});
