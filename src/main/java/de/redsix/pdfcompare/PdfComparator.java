/*
 * Copyright 2016 Malte Finsterwalder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.redsix.pdfcompare;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

public class PdfComparator {

    private static final int DPI = 300;
    private static final int MARKER_RGB = Color.MAGENTA.getRGB();
    private static final int EXTRA_RGB = Color.GREEN.getRGB();
    private static final int MISSING_RGB = Color.RED.getRGB();
    private static final int MARKER_WIDTH = 20;

    public CompareResult compare(String expectedPdfFilename, String actualPdfFilename) throws IOException {
        if (expectedPdfFilename.equals(actualPdfFilename)) {
            return new CompareResult();
        }
        return compare(new FileInputStream(expectedPdfFilename), new FileInputStream(actualPdfFilename));
    }

    public CompareResult compare(final File expectedFile, final File actualFile) throws IOException {
        if (expectedFile.equals(actualFile)) {
            return new CompareResult();
        }
        return compare(new FileInputStream(expectedFile), new FileInputStream(actualFile));
    }

    public CompareResult compare(InputStream expectedPdfIS, InputStream actualPdfIS) throws IOException {
        final CompareResult result = new CompareResult();
        if (expectedPdfIS.equals(actualPdfIS)) {
            return result;
        }
        try (PDDocument expectedDocument = PDDocument.load(expectedPdfIS)) {
            PDFRenderer expectedPdfRenderer = new PDFRenderer(expectedDocument);
            try (PDDocument actualDocument = PDDocument.load(actualPdfIS)) {
                PDFRenderer actualPdfRenderer = new PDFRenderer(actualDocument);
                final int minPageCount = Math.min(expectedDocument.getNumberOfPages(), actualDocument.getNumberOfPages());
                for (int pageIndex = 0; pageIndex < minPageCount; pageIndex++) {
                    BufferedImage expectedImage = renderPageAsImage(expectedPdfRenderer, pageIndex);
                    BufferedImage actualImage = renderPageAsImage(actualPdfRenderer, pageIndex);
                    compare(expectedImage, actualImage, pageIndex, result);
                }
                if (expectedDocument.getNumberOfPages() > minPageCount) {
                    addExtraPages(expectedDocument, expectedPdfRenderer, minPageCount, result, MISSING_RGB, true);
                } else if (actualDocument.getNumberOfPages() > minPageCount) {
                    addExtraPages(actualDocument, actualPdfRenderer, minPageCount, result, EXTRA_RGB, false);
                }
            }
        }
        return result;
    }

    public static BufferedImage deepCopy(BufferedImage image) {
        return new BufferedImage(image.getColorModel(), image.copyData(null), image.getColorModel().isAlphaPremultiplied(), null);
    }

    private void addExtraPages(final PDDocument document, final PDFRenderer pdfRenderer, final int minPageCount, final CompareResult result,
            final int color, final boolean expected) throws IOException {
        for (int pageIndex = minPageCount; pageIndex < document.getNumberOfPages(); pageIndex++) {
            BufferedImage image = renderPageAsImage(pdfRenderer, pageIndex);
            final DataBuffer dataBuffer = image.getRaster().getDataBuffer();
            for (int i = 0; i < image.getWidth() * MARKER_WIDTH; i++) {
                dataBuffer.setElem(i, color);
            }
            for (int i = 0; i < image.getHeight(); i++) {
                for (int j = 0; j < MARKER_WIDTH; j++) {
                    dataBuffer.setElem(i * image.getWidth() + j, color);
                }
            }
            if (expected) {
                result.addPageThatsNotEqual(pageIndex, image, blank(image), image);
            }
            else {
                result.addPageThatsNotEqual(pageIndex, blank(image), image, image);
            }
        }
    }

    private BufferedImage blank(final BufferedImage image) {
        return new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
    }

    private BufferedImage renderPageAsImage(final PDFRenderer expectedPdfRenderer, final int pageIndex) throws IOException {
        return expectedPdfRenderer.renderImageWithDPI(pageIndex, DPI);
    }

    private void compare(final BufferedImage expectedImage, final BufferedImage actualImage, final int pageIndex,
            final CompareResult result) {
        Optional<BufferedImage> diffImage = diffImages(expectedImage, actualImage);
        if (diffImage.isPresent()) {
            result.addPageThatsNotEqual(pageIndex, expectedImage, actualImage, diffImage.get());
        } else {
            result.addPageThatsEqual(pageIndex, expectedImage);
        }
    }

    /**
     * Creates a new ResultImage and returns that image or an empty Optional
     */
    private Optional<BufferedImage> diffImages(final BufferedImage expectedImage, final BufferedImage actualImage) {
        final DataBuffer expectedBuffer = expectedImage.getRaster().getDataBuffer();
        final DataBuffer actualBuffer = actualImage.getRaster().getDataBuffer();

        final int expectedImageWidth = expectedImage.getWidth();
        final int expectedImageHeight = expectedImage.getHeight();
        final int actualImageWidth = actualImage.getWidth();
        final int actualImageHeight = actualImage.getHeight();

        final int resultImageWidth = Math.max(expectedImageWidth, actualImageWidth);
        final int resultImageHeight = Math.max(expectedImageHeight, actualImageHeight);
        final BufferedImage resultImage = new BufferedImage(resultImageWidth, resultImageHeight, actualImage.getType());
        final DataBuffer resultBuffer = resultImage.getRaster().getDataBuffer();

        int expectedElement = 0;
        int actualElement = 0;
        boolean diffFound = false;

        for (int y = 0; y < resultImageHeight; y++) {
            final int expectedLineOffset = y * expectedImageWidth;
            final int actualLineOffset = y * actualImageWidth;
            final int resultLineOffset = y * resultImageWidth;
            for (int x = 0; x < resultImageWidth; x++) {
                if (x < expectedImageWidth && y < expectedImageHeight) {
                    expectedElement = expectedBuffer.getElem(x + expectedLineOffset);
                } else {
                    expectedElement = 0;
                    diffFound = true;
                }
                if (x < actualImageWidth && y < actualImageHeight) {
                    actualElement = actualBuffer.getElem(x + actualLineOffset);
                } else {
                    actualElement = 0;
                    diffFound = true;
                }
                if (expectedElement != actualElement) {
                    diffFound = true;
                    int expectedDarkness = calcDarkness(expectedElement);
                    int actualDarkness = calcDarkness(actualElement);
                    int element;
                    if (expectedDarkness > actualDarkness) {
                        element = createElement(Math.max(50, Math.min(expectedDarkness / 3, 255)), 0, 0);
                    } else {
                        element = createElement(0, Math.max(50, Math.min(actualDarkness / 3, 255)), 0);
                    }
                    resultBuffer.setElem(x + resultLineOffset, element);
                    mark(resultBuffer, x, y, resultImageWidth, MARKER_RGB);
                } else {
                    resultBuffer.setElem(x + resultLineOffset, fadeElement(expectedElement));
                }
            }
        }
        if (diffFound) {
            return Optional.of(resultImage);
        }
        return Optional.empty();
    }

    private static int getRed(final int element) {
        return element & 0xff0000 >> 16;
    }

    private static int getGreen(final int element) {
        return element & 0xff00 >> 8;
    }

    private static int getBlue(final int element) {
        return element & 0xff;
    }

    private int createElement(final int red, final int green, final int blue) {
        return ((red & 0xff) << 16) | ((green & 0xff) << 8) | blue & 0xff;
    }

    private static void blankImage(final BufferedImage resultImage) {
        Graphics2D graphics = resultImage.createGraphics();
        graphics.setPaint(Color.white);
        graphics.fillRect(0, 0, resultImage.getWidth(), resultImage.getHeight());
    }

    private static int fadeElement(final int i) {
        return fade(getRed(i)) << 16
                | fade(getGreen(i)) << 8
                | fade(getBlue(i));
    }

    private static int fade(final int i) {
        return i + ((255 - i) * 4 / 5);
    }

    private static int calcDarkness(final int element) {
        return getRed(element) + getGreen(element) + getRed(element);
    }

    private static void mark(final DataBuffer image, final int x, final int y, final int imageWidth, final int markerRGB) {
        final int yOffset = y * imageWidth;
        for (int i = 0; i < MARKER_WIDTH; i++) {
            image.setElem(x + i * imageWidth, markerRGB);
            image.setElem(i + yOffset, markerRGB);
        }
    }
}
