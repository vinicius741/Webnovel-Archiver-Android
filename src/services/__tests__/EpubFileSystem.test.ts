import { EpubFileSystem } from '../epub/EpubFileSystem';
import JSZip from 'jszip';

jest.mock('jszip');

describe('EpubFileSystem', () => {
    let mockZip: any;
    let fileSystem: EpubFileSystem;

    beforeEach(() => {
        jest.clearAllMocks();
        mockZip = {
            file: jest.fn(),
            folder: jest.fn(),
            generateAsync: jest.fn().mockResolvedValue('base64string'),
        };
        (JSZip as unknown as jest.Mock).mockReturnValue(mockZip);
    });

    describe('create', () => {
        it('should create a new EpubFileSystem instance', () => {
            const fs = EpubFileSystem.create();

            expect(fs).toBeInstanceOf(EpubFileSystem);
            expect(JSZip).toHaveBeenCalled();
        });

        it('should wrap JSZip instance', () => {
            const fs = EpubFileSystem.create();

            expect(fs).toBeDefined();
        });
    });

    describe('createMimetype', () => {
        it('should create mimetype file with STORE compression', () => {
            fileSystem = EpubFileSystem.create();
            fileSystem.createMimetype();

            expect(mockZip.file).toHaveBeenCalledWith(
                'mimetype',
                'application/epub+zip',
                { compression: 'STORE' }
            );
        });
    });

    describe('createMetaInf', () => {
        it('should create META-INF folder', () => {
            const mockMetaInf = { file: jest.fn() };
            mockZip.folder.mockReturnValue(mockMetaInf);

            fileSystem = EpubFileSystem.create();
            fileSystem.createMetaInf();

            expect(mockZip.folder).toHaveBeenCalledWith('META-INF');
            expect(mockMetaInf.file).toHaveBeenCalledWith(
                'container.xml',
                expect.stringContaining('<container')
            );
        });

        it('should generate valid container.xml', () => {
            const mockMetaInf = { file: jest.fn() };
            mockZip.folder.mockReturnValue(mockMetaInf);

            fileSystem = EpubFileSystem.create();
            fileSystem.createMetaInf();

            const containerXml = mockMetaInf.file.mock.calls[0][1];
            expect(containerXml).toContain('urn:oasis:names:tc:opendocument:xmlns:container');
            expect(containerXml).toContain('OEBPS/content.opf');
        });
    });

    describe('createOEBPSFolder', () => {
        it('should create OEBPS folder', () => {
            const mockOebps = { file: jest.fn() };
            mockZip.folder.mockReturnValue(mockOebps);

            fileSystem = EpubFileSystem.create();
            const oebps = fileSystem.createOEBPSFolder();

            expect(mockZip.folder).toHaveBeenCalledWith('OEBPS');
            expect(oebps).toBe(mockOebps);
        });

        it('should throw if folder creation fails', () => {
            mockZip.folder.mockReturnValue(null);

            fileSystem = EpubFileSystem.create();

            expect(() => fileSystem.createOEBPSFolder()).toThrow('Failed to create OEBPS folder');
        });
    });

    describe('generate', () => {
        it('should generate base64 output', async () => {
            fileSystem = EpubFileSystem.create();
            const result = await fileSystem.generate();

            expect(mockZip.generateAsync).toHaveBeenCalledWith({ type: 'base64' });
            expect(result).toBe('base64string');
        });
    });
});
