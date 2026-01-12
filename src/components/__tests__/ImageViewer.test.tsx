import React from 'react';
import { render } from '@testing-library/react-native';
import ImageViewer from '../ImageViewer';

jest.mock('react-native-image-viewing', () => {
    return () => null;
});

describe('ImageView', () => {
    const mockImages = [
        { uri: 'https://example.com/image1.jpg' },
        { uri: 'https://example.com/image2.jpg' },
    ];

    it('should not render when visible is false', () => {
        const result = render(
            <ImageViewer
                visible={false}
                images={mockImages}
                imageIndex={0}
                onRequestClose={jest.fn()}
            />
        );

        expect(result.queryByTestId('modal')).toBeNull();
    });
});