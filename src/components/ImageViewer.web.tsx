import React from 'react';
import { Modal, Image, StyleSheet, TouchableOpacity, Text, View } from 'react-native';

const ImageView = ({ visible, images, imageIndex, onRequestClose, testID }: any) => {
  if (!visible) return null;
  const image = images && images.length > 0 ? images[imageIndex || 0] : null;

  return (
    <Modal visible={visible} transparent={true} onRequestClose={onRequestClose} animationType="fade" testID={testID || 'modal'}>
      <View style={styles.container} testID="container-view">
        <TouchableOpacity style={styles.closeButton} onPress={onRequestClose} testID="close-button">
           <Text style={styles.closeText}>✕</Text>
        </TouchableOpacity>
        {image && <Image source={image} style={styles.image} resizeMode="contain" testID="image" />}
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.9)',
        justifyContent: 'center',
        alignItems: 'center',
        position: 'absolute', // React Native Web treats fixed/absolute similarly for full screen modals, or use 'fixed' as any
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 9999,
    },
    image: {
        width: '90%',
        height: '90%',
    },
    closeButton: {
        position: 'absolute',
        top: 20,
        right: 20,
        zIndex: 1,
        padding: 10,
        backgroundColor: 'rgba(0,0,0,0.5)',
        borderRadius: 20,
        width: 40,
        height: 40,
        justifyContent: 'center',
        alignItems: 'center',
    },
    closeText: {
        color: 'white',
        fontSize: 20,
        fontWeight: 'bold',
        lineHeight: 20,
    }
});

export default ImageView;
