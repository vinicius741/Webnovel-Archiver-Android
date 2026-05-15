import React from "react";
import { StyleSheet, View } from "react-native";
import ImageView from "react-native-image-viewing";
import { IconButton, Text } from "react-native-paper";

type NativeImageViewerProps = React.ComponentProps<typeof ImageView>;

const ViewerHeader = ({ onRequestClose }: Pick<NativeImageViewerProps, "onRequestClose">) => {
  return (
    <View style={styles.header}>
      <Text style={styles.hint}>Pinch or double tap to zoom</Text>
      <IconButton
        icon="close"
        size={28}
        mode="contained-tonal"
        iconColor="#FFFFFF"
        containerColor="rgba(0, 0, 0, 0.55)"
        onPress={onRequestClose}
        testID="image-viewer-close-button"
        accessibilityLabel="Close image viewer"
      />
    </View>
  );
};

const ImageViewerNative = (props: NativeImageViewerProps) => {
  const { onRequestClose } = props;

  return (
    <ImageView
      {...props}
      animationType="fade"
      backgroundColor="#000000"
      presentationStyle="fullScreen"
      swipeToCloseEnabled={false}
      doubleTapToZoomEnabled={true}
      HeaderComponent={() => <ViewerHeader onRequestClose={onRequestClose} />}
    />
  );
};

const styles = StyleSheet.create({
  header: {
    width: "100%",
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingTop: 16,
    paddingHorizontal: 12,
  },
  hint: {
    color: "#FFFFFF",
    fontSize: 14,
    marginLeft: 8,
  },
});

export default ImageViewerNative;
