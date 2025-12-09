import React, { createContext, useContext, useState, ReactNode } from 'react';
import { Button, Dialog, Portal, Text, useTheme } from 'react-native-paper';
import { View } from 'react-native';

interface AlertButton {
  text: string;
  onPress?: () => void;
  style?: 'default' | 'cancel' | 'destructive';
}

interface AlertContextType {
  showAlert: (title: string, message?: string, buttons?: AlertButton[]) => void;
}

const AlertContext = createContext<AlertContextType | undefined>(undefined);

export const AlertProvider = ({ children }: { children: ReactNode }) => {
  const [visible, setVisible] = useState(false);
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState<string | undefined>('');
  const [buttons, setButtons] = useState<AlertButton[]>([]);
  const theme = useTheme();

  const showAlert = (newTitle: string, newMessage?: string, newButtons: AlertButton[] = []) => {
    setTitle(newTitle);
    setMessage(newMessage);
    
    // If no buttons provided, add a default "OK" button
    if (!newButtons || newButtons.length === 0) {
        setButtons([{ text: 'OK', onPress: () => {} }]);
    } else {
        setButtons(newButtons);
    }
    
    setVisible(true);
  };

  const hideAlert = () => {
    setVisible(false);
  };

  return (
    <AlertContext.Provider value={{ showAlert }}>
      {children}
      <Portal>
        <Dialog 
            visible={visible} 
            onDismiss={hideAlert} 
            style={{ 
                backgroundColor: theme.colors.elevation.level3, 
                borderRadius: 16 
            }}
        >
          <Dialog.Icon icon="alert-circle-outline" />
          <Dialog.Title style={{ textAlign: 'center' }}>{title}</Dialog.Title>
          <Dialog.Content>
            {message ? (
               <Text variant="bodyMedium" style={{ textAlign: 'center', color: theme.colors.onSurfaceVariant }}>
                 {message}
               </Text>
            ) : null}
          </Dialog.Content>
          <Dialog.Actions style={{ justifyContent: 'center', paddingBottom: 16 }}>
             <View style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 8 }}>
                {buttons.map((btn, index) => (
                    <Button 
                        key={index} 
                        mode={btn.style === 'cancel' ? 'outlined' : 'text'}
                        onPress={() => {
                            if(btn.onPress) btn.onPress();
                            hideAlert();
                        }}
                        textColor={btn.style === 'destructive' ? theme.colors.error : theme.colors.primary}
                        style={{ minWidth: 80 }}
                    >
                        {btn.text}
                    </Button>
                ))}
             </View>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </AlertContext.Provider>
  );
};

export const useAppAlert = () => {
  const context = useContext(AlertContext);
  if (!context) {
    throw new Error('useAppAlert must be used within an AlertProvider');
  }
  return context;
};
