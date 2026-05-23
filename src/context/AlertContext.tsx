import React, {
  createContext,
  useContext,
  useState,
  ReactNode,
  useRef,
  useEffect,
} from "react";
import { Dialog, Portal, Text, useTheme } from "react-native-paper";
import { AppButton } from "../components/theme/AppButton";
import { useAppTheme } from "../theme/useAppTheme";
import { View } from "react-native";

interface AlertButton {
  text: string;
  onPress?: () => void;
  style?: "default" | "cancel" | "destructive";
}

interface AlertContextType {
  showAlert: (title: string, message?: string, buttons?: AlertButton[]) => void;
}

const AlertContext = createContext<AlertContextType | undefined>(undefined);

export const AlertProvider = ({ children }: { children: ReactNode }) => {
  const [visible, setVisible] = useState(false);
  const [portalMounted, setPortalMounted] = useState(false);
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState<string | undefined>("");
  const [buttons, setButtons] = useState<AlertButton[]>([]);
  const theme = useTheme();
  const appTheme = useAppTheme();
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  const showAlert = (
    newTitle: string,
    newMessage?: string,
    newButtons: AlertButton[] = [],
  ) => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }

    setTitle(newTitle);
    setMessage(newMessage);

    // If no buttons provided, add a default "OK" button
    if (!newButtons || newButtons.length === 0) {
      setButtons([{ text: "OK", onPress: () => {} }]);
    } else {
      setButtons(newButtons);
    }

    setPortalMounted(true);
    setVisible(true);
  };

  const hideAlert = () => {
    setVisible(false);

    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }

    timeoutRef.current = setTimeout(() => {
      setPortalMounted(false);
      timeoutRef.current = null;
    }, 300); // 300ms to allow the dismiss animation to complete
  };

  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  return (
    <AlertContext.Provider value={{ showAlert }}>
      {children}
      {portalMounted && (
        <Portal>
          <Dialog
            visible={visible}
            onDismiss={hideAlert}
            style={{
              backgroundColor: theme.colors.elevation.level3,
              borderRadius: appTheme.shapes.dialogRadius,
            }}
          >
            <Dialog.Icon icon="alert-circle-outline" />
            <Dialog.Title style={{ textAlign: "center" }}>{title}</Dialog.Title>
            <Dialog.Content>
              {message ? (
                <Text
                  variant="bodyMedium"
                  style={{
                    textAlign: "center",
                    color: theme.colors.onSurfaceVariant,
                  }}
                >
                  {message}
                </Text>
              ) : null}
            </Dialog.Content>
            <Dialog.Actions
              style={{ justifyContent: "center", paddingBottom: 16 }}
            >
              <View
                style={{
                  flexDirection: "row",
                  flexWrap: "wrap",
                  justifyContent: "center",
                  gap: 8,
                }}
              >
                {buttons.map((btn, index) => (
                  <AppButton
                    key={index}
                    mode={btn.style === "cancel" ? "outlined" : "text"}
                    onPress={() => {
                      if (btn.onPress) btn.onPress();
                      hideAlert();
                    }}
                    textColor={
                      btn.style === "destructive"
                        ? theme.colors.error
                        : theme.colors.primary
                    }
                    style={{ minWidth: 80 }}
                  >
                    {btn.text}
                  </AppButton>
                ))}
              </View>
            </Dialog.Actions>
          </Dialog>
        </Portal>
      )}
    </AlertContext.Provider>
  );
};

export const useAppAlert = () => {
  const context = useContext(AlertContext);
  if (!context) {
    throw new Error("useAppAlert must be used within an AlertProvider");
  }
  return context;
};
