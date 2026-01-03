import ImageViewerNative from './ImageViewer.native';
import ImageViewerWeb from './ImageViewer.web';
import { Platform } from 'react-native';

const ImageViewer = Platform.OS === 'web' ? ImageViewerWeb : ImageViewerNative;

export default ImageViewer;
