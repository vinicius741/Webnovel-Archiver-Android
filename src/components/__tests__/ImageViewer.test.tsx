import React from "react";
import { fireEvent, render } from "@testing-library/react-native";
import ImageViewer from "../ImageViewer";

const mockImageViewing = jest.fn<React.ReactNode, [unknown]>(() => null);

jest.mock("react-native-image-viewing", () => ({
  __esModule: true,
  default: (props: unknown) => mockImageViewing(props),
}));

describe("ImageView", () => {
  const mockImages = [
    { uri: "https://example.com/image1.jpg" },
    { uri: "https://example.com/image2.jpg" },
  ];

  beforeEach(() => {
    mockImageViewing.mockClear();
  });

  it("should not render when visible is false", () => {
    const result = render(
      <ImageViewer
        visible={false}
        images={mockImages}
        imageIndex={0}
        onRequestClose={jest.fn()}
      />,
    );

    expect(result.queryByTestId("modal")).toBeNull();
  });

  it("should configure a stable native viewer experience", () => {
    const onRequestClose = jest.fn();

    render(
      <ImageViewer
        visible={true}
        images={mockImages}
        imageIndex={0}
        onRequestClose={onRequestClose}
      />,
    );

    expect(mockImageViewing).toHaveBeenCalledWith(
      expect.objectContaining({
        visible: true,
        images: mockImages,
        imageIndex: 0,
        onRequestClose,
        animationType: "fade",
        backgroundColor: "#000000",
        presentationStyle: "fullScreen",
        swipeToCloseEnabled: false,
        doubleTapToZoomEnabled: true,
        HeaderComponent: expect.any(Function),
      }),
    );
  });

  it("should render a close button in the native viewer header", () => {
    const onRequestClose = jest.fn();

    render(
      <ImageViewer
        visible={true}
        images={mockImages}
        imageIndex={0}
        onRequestClose={onRequestClose}
      />,
    );

    const firstCall = mockImageViewing.mock.calls[0]?.[0] as
      | { HeaderComponent?: React.ComponentType<{ imageIndex: number }> }
      | undefined;

    expect(firstCall?.HeaderComponent).toBeDefined();

    const HeaderComponent = firstCall?.HeaderComponent;
    if (!HeaderComponent) {
      throw new Error("HeaderComponent was not provided to ImageView");
    }

    const { getByTestId } = render(<HeaderComponent imageIndex={0} />);

    fireEvent.press(getByTestId("image-viewer-close-button"));

    expect(onRequestClose).toHaveBeenCalledTimes(1);
  });
});
