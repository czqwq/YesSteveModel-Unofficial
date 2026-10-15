package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.avif.dec.Entropy;
import rip.ysm.imagestream.avif.dec.Mem;
import rip.ysm.imagestream.avif.dec.Obu;
import rip.ysm.imagestream.avif.dec.Symbol;
import rip.ysm.imagestream.avif.dec.Yuv;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Stack;
import rip.ysm.imagestream.internal.LogWriter;

class D {
    static final int kBitdepth8 = 8;
    static final int kBitdepth10 = 10;
    static final int kBitdepth12 = 12;
    static final int kInvalidMvValue = Short.MIN_VALUE;
    static final int kCdfMaxProbability = 32768;
    static final int kBlockWidthCount = 5;
    static final int kMaxSegments = 8;
    static final int kMinQuantizer = 0;
    static final int kMinLossyQuantizer = 1;
    static final int kMaxQuantizer = 255;
    static final int kNumQuantizerLevelsForQuantizerMatrix = 15;
    static final int kFrameLfCount = 4;
    static final int kMaxLoopFilterValue = 63;
    static final int kNum4x4In64x64 = 256;
    static final int kMaxAngleDelta = 3;
    static final int kDirectionalIntraModes = 8;
    static final int kMaxSuperBlockSizeLog2 = 7;
    static final int kMinSuperBlockSizeLog2 = 6;
    static final int kGlobalMotionReadControl = 3;
    static final int kSuperResScaleNumerator = 8;
    static final int kBooleanSymbolCount = 2;
    static final int kRestorationTypeSymbolCount = 3;
    static final int kSgrProjParamsBits = 4;
    static final int kSgrProjPrecisionBits = 7;
    static final int kSgrProjScaleBits = 20;
    static final int kSgrProjReciprocalBits = 12;
    static final int kSgrProjSgrBits = 8;
    static final int kSgrProjRestoreBits = 4;
    static final int kRestorationHorizontalBorder = 4;
    static final int kRestorationVerticalBorder = 2;
    static final int kCdefBorder = 2;
    static final int kConvolveBorderLeftTop = 3;
    static final int kConvolveBorderRight = 8;
    static final int kConvolveScaleBorderRight = 15;
    static final int kConvolveBorderBottom = 4;
    static final int kSubPixelTaps = 8;
    static final int kWienerFilterBits = 7;
    static final int kWienerFilterTaps = 7;
    static final int kMaxPaletteSize = 8;
    static final int kMinPaletteSize = 2;
    static final int kMaxPaletteSquare = 64;
    static final int kBorderPixels = 64;
    static final int kBorderPixelsFilmGrain = 32;
    static final int kMinLeftBorderPixels = 13;
    static final int kMinRightBorderPixels = 13;
    static final int kMinTopBorderPixels = 13;
    static final int kMinBottomBorderPixels = 13;
    static final int kWarpedModelPrecisionBits = 16;
    static final int kMaxRefMvStackSize = 8;
    static final int kMaxLeastSquaresSamples = 8;
    static final int kMaxTemporalMvCandidates = 19;
    static final int kMaxTemporalMvCandidatesWithPadding = 20;
    static final int kMaxSuperBlockSizeInPixels = 128;
    static final int kMaxScaledSuperBlockSizeInPixels = 256;
    static final int kMaxSuperBlockSizeSquareInPixels = 16384;
    static final int kNum4x4InLoopFilterUnit = 16;
    static final int kNum4x4InLoopRestorationUnit = 16;
    static final int kProjectionMvClamp = 16383;
    static final int kProjectionMvMaxHorizontalOffset = 8;
    static final int kCdefUnitSize = 64;
    static final int kCdefUnitSizeWithBorders = 68;
    static final int kRestorationUnitOffset = 8;
    static final int kRestorationUnitHeight = 64;
    static final int kRestorationUnitWidth = 256;
    static final int kRestorationUnitHeightWithBorders = 68;
    static final int kRestorationUnitWidthWithBorders = 264;
    static final int kSuperResFilterBits = 6;
    static final int kSuperResFilterShifts = 64;
    static final int kSuperResFilterTaps = 8;
    static final int kSuperResScaleBits = 14;
    static final int kSuperResExtraBits = 8;
    static final int kSuperResScaleMask = 16383;
    static final int kSuperResHorizontalBorder = 4;
    static final int kSuperResVerticalBorder = 1;
    static final int kSuperResHorizontalPadding = 16;
    static final int kFilterBits = 7;
    static final int kSubPixelBits = 4;
    static final int kSubPixelMask = 15;
    static final int kScaleSubPixelBits = 10;
    static final int kWarpParamRoundingBits = 6;
    static final int kDivisorLookupBits = 8;
    static final int kDivisorLookupPrecisionBits = 14;
    static final int kWarpedPixelPrecisionShifts = 64;
    static final int kResidualPaddingVertical = 4;
    static final int kWedgeMaskMasterSize = 64;
    static final int kMaxFrameDistance = 31;
    static final int kReferenceFrameScalePrecision = 14;
    static final int kNumWienerCoefficients = 3;
    static final int kLoopFilterMaxModeDeltas = 2;
    static final int kMaxCdefStrengths = 8;
    static final int kCdefLargeValue = 16384;
    static final int kMaxTileColumns = 64;
    static final int kMaxTileRows = 64;
    static final int kMaxOperatingPoints = 32;
    static final int kMaxLayers = 32;
    static final int kCacheLineSize = 64;
    static final int kInterRoundBitsHorizontal = 3;
    static final int kInterRoundBitsHorizontal12bpp = 5;
    static final int kInterRoundBitsCompoundVertical = 7;
    static final int kInterRoundBitsVertical = 11;
    static final int kInterRoundBitsVertical12bpp = 9;
    static final int kCompoundOffset = 24576;
    static final int kPartitionContexts = 4;
    static final int kSegmentIdContexts = 3;
    static final int kUsePredictedSegmentIdContexts = 3;
    static final int kSkipContexts = 3;
    static final int kSkipModeContexts = 3;
    static final int kBooleanFieldCdfSize = 3;
    static final int kDeltaSymbolCount = 4;
    static final int kIntraModeContexts = 5;
    static final int kYModeContexts = 4;
    static final int kAngleDeltaSymbolCount = 7;
    static final int kCflAlphaSignsSymbolCount = 8;
    static final int kCflAlphaContexts = 6;
    static final int kCflAlphaSymbolCount = 16;
    static final int kTxDepthContexts = 3;
    static final int kMaxTxDepthSymbolCount = 3;
    static final int kTxSplitContexts = 21;
    static final int kCoefficientQuantizerContexts = 4;
    static final int kNumSquareTransformSizes = 5;
    static final int kAllZeroContexts = 13;
    static final int kNumExtendedTransformSizes = 4;
    static final int kEobPtContexts = 2;
    static final int kEobPt16SymbolCount = 5;
    static final int kEobPt32SymbolCount = 6;
    static final int kEobPt64SymbolCount = 7;
    static final int kEobPt128SymbolCount = 8;
    static final int kEobPt256SymbolCount = 9;
    static final int kEobPt512SymbolCount = 10;
    static final int kEobPt1024SymbolCount = 11;
    static final int kEobExtraContexts = 9;
    static final int kCoeffBaseEobContexts = 4;
    static final int kCoeffBaseEobSymbolCount = 3;
    static final int kCoeffBaseContexts = 42;
    static final int kCoeffBaseSymbolCount = 4;
    static final int kCoeffBaseRangeContexts = 21;
    static final int kCoeffBaseRangeSymbolCount = 4;
    static final int kDcSignContexts = 3;
    static final int kPaletteBlockSizeContexts = 7;
    static final int kPaletteYModeContexts = 3;
    static final int kPaletteUVModeContexts = 2;
    static final int kPaletteSizeSymbolCount = 7;
    static final int kPaletteColorIndexContexts = 5;
    static final int kPaletteColorIndexSymbolCount = 8;
    static final int kIsInterContexts = 4;
    static final int kUseCompoundReferenceContexts = 5;
    static final int kCompoundReferenceTypeContexts = 5;
    static final int kReferenceContexts = 3;
    static final int kCompoundPredictionModeContexts = 8;
    static final int kNewMvContexts = 6;
    static final int kZeroMvContexts = 2;
    static final int kReferenceMvContexts = 6;
    static final int kRefMvIndexContexts = 3;
    static final int kInterIntraContexts = 3;
    static final int kWedgeIndexSymbolCount = 16;
    static final int kIsExplicitCompoundTypeContexts = 6;
    static final int kIsCompoundTypeAverageContexts = 6;
    static final int kInterpolationFilterContexts = 16;
    static final int kMvContexts = 2;
    static final int kMvClassSymbolCount = 11;
    static final int kMvFractionSymbolCount = 4;
    static final int kMvBitSymbolCount = 10;
    static final int kNumMvComponents = 2;
    static final int kSgrStride = 288;
    static final int kFrameKey = 0;
    static final int kFrameInter = 1;
    static final int kFrameIntraOnly = 2;
    static final int kFrameSwitch = 3;
    static final int kPlaneY = 0;
    static final int kPlaneU = 1;
    static final int kPlaneV = 2;
    static final int kMaxPlanes = 3;
    static final int kMaxPlanesMonochrome = 1;
    static final int kPlaneTypeY = 0;
    static final int kPlaneTypeUV = 1;
    static final int kNumPlaneTypes = 2;
    static final int kReferenceFrameNone = -1;
    static final int kReferenceFrameIntra = 0;
    static final int kReferenceFrameLast = 1;
    static final int kReferenceFrameLast2 = 2;
    static final int kReferenceFrameLast3 = 3;
    static final int kReferenceFrameGolden = 4;
    static final int kReferenceFrameBackward = 5;
    static final int kReferenceFrameAlternate2 = 6;
    static final int kReferenceFrameAlternate = 7;
    static final int kNumReferenceFrameTypes = 8;
    static final int kNumInterReferenceFrameTypes = 7;
    static final int kNumForwardReferenceTypes = 4;
    static final int kNumBackwardReferenceTypes = 3;
    static final int kExplicitUnidirectionalCompoundReferences = 4;
    static final int kUnidirectionalCompoundReferences = 9;
    static final int kBlock4x4 = 0;
    static final int kBlock4x8 = 1;
    static final int kBlock4x16 = 2;
    static final int kBlock8x4 = 3;
    static final int kBlock8x8 = 4;
    static final int kBlock8x16 = 5;
    static final int kBlock8x32 = 6;
    static final int kBlock16x4 = 7;
    static final int kBlock16x8 = 8;
    static final int kBlock16x16 = 9;
    static final int kBlock16x32 = 10;
    static final int kBlock16x64 = 11;
    static final int kBlock32x8 = 12;
    static final int kBlock32x16 = 13;
    static final int kBlock32x32 = 14;
    static final int kBlock32x64 = 15;
    static final int kBlock64x16 = 16;
    static final int kBlock64x32 = 17;
    static final int kBlock64x64 = 18;
    static final int kBlock64x128 = 19;
    static final int kBlock128x64 = 20;
    static final int kBlock128x128 = 21;
    static final int kMaxBlockSizes = 22;
    static final int kBlockInvalid = 23;
    static final int kPartitionNone = 0;
    static final int kPartitionHorizontal = 1;
    static final int kPartitionVertical = 2;
    static final int kPartitionSplit = 3;
    static final int kPartitionHorizontalWithTopSplit = 4;
    static final int kPartitionHorizontalWithBottomSplit = 5;
    static final int kPartitionVerticalWithLeftSplit = 6;
    static final int kPartitionVerticalWithRightSplit = 7;
    static final int kPartitionHorizontal4 = 8;
    static final int kPartitionVertical4 = 9;
    static final int kMaxPartitionTypes = 10;
    static final int kPredictionModeDc = 0;
    static final int kPredictionModeVertical = 1;
    static final int kPredictionModeHorizontal = 2;
    static final int kPredictionModeD45 = 3;
    static final int kPredictionModeD135 = 4;
    static final int kPredictionModeD113 = 5;
    static final int kPredictionModeD157 = 6;
    static final int kPredictionModeD203 = 7;
    static final int kPredictionModeD67 = 8;
    static final int kPredictionModeSmooth = 9;
    static final int kPredictionModeSmoothVertical = 10;
    static final int kPredictionModeSmoothHorizontal = 11;
    static final int kPredictionModePaeth = 12;
    static final int kPredictionModeChromaFromLuma = 13;
    static final int kPredictionModeNearestMv = 14;
    static final int kPredictionModeNearMv = 15;
    static final int kPredictionModeGlobalMv = 16;
    static final int kPredictionModeNewMv = 17;
    static final int kPredictionModeNearestNearestMv = 18;
    static final int kPredictionModeNearNearMv = 19;
    static final int kPredictionModeNearestNewMv = 20;
    static final int kPredictionModeNewNearestMv = 21;
    static final int kPredictionModeNearNewMv = 22;
    static final int kPredictionModeNewNearMv = 23;
    static final int kPredictionModeGlobalGlobalMv = 24;
    static final int kPredictionModeNewNewMv = 25;
    static final int kNumPredictionModes = 26;
    static final int kNumCompoundInterPredictionModes = 8;
    static final int kIntraPredictionModesY = 13;
    static final int kIntraPredictionModesUV = 14;
    static final int kPredictionModeInvalid = 255;
    static final int kInterIntraModeDc = 0;
    static final int kInterIntraModeVertical = 1;
    static final int kInterIntraModeHorizontal = 2;
    static final int kInterIntraModeSmooth = 3;
    static final int kNumInterIntraModes = 4;
    static final int kMotionModeSimple = 0;
    static final int kMotionModeObmc = 1;
    static final int kMotionModeLocalWarp = 2;
    static final int kNumMotionModes = 3;
    static final int kTxModeOnly4x4 = 0;
    static final int kTxModeLargest = 1;
    static final int kTxModeSelect = 2;
    static final int kNumTxModes = 3;
    static final int kTransformTypeDctDct = 0;
    static final int kTransformTypeAdstDct = 1;
    static final int kTransformTypeDctAdst = 2;
    static final int kTransformTypeAdstAdst = 3;
    static final int kTransformTypeFlipadstDct = 4;
    static final int kTransformTypeDctFlipadst = 5;
    static final int kTransformTypeFlipadstFlipadst = 6;
    static final int kTransformTypeAdstFlipadst = 7;
    static final int kTransformTypeFlipadstAdst = 8;
    static final int kTransformTypeIdentityIdentity = 9;
    static final int kTransformTypeIdentityDct = 10;
    static final int kTransformTypeDctIdentity = 11;
    static final int kTransformTypeIdentityAdst = 12;
    static final int kTransformTypeAdstIdentity = 13;
    static final int kTransformTypeIdentityFlipadst = 14;
    static final int kTransformTypeFlipadstIdentity = 15;
    static final int kNumTransformTypes = 16;
    static BitMaskSet kTransformFlipColumnsMask = new BitMaskSet(4, 8, 15, 6);
    static BitMaskSet kTransformFlipRowsMask = new BitMaskSet(5, 7, 14, 6);
    static final int kTransformSize4x4 = 0;
    static final int kTransformSize4x8 = 1;
    static final int kTransformSize4x16 = 2;
    static final int kTransformSize8x4 = 3;
    static final int kTransformSize8x8 = 4;
    static final int kTransformSize8x16 = 5;
    static final int kTransformSize8x32 = 6;
    static final int kTransformSize16x4 = 7;
    static final int kTransformSize16x8 = 8;
    static final int kTransformSize16x16 = 9;
    static final int kTransformSize16x32 = 10;
    static final int kTransformSize16x64 = 11;
    static final int kTransformSize32x8 = 12;
    static final int kTransformSize32x16 = 13;
    static final int kTransformSize32x32 = 14;
    static final int kTransformSize32x64 = 15;
    static final int kTransformSize64x16 = 16;
    static final int kTransformSize64x32 = 17;
    static final int kTransformSize64x64 = 18;
    static final int kNumTransformSizes = 19;
    static final int kTransformSetDctOnly = 0;
    static final int kTransformSetIntra1 = 1;
    static final int kTransformSetIntra2 = 2;
    static final int kTransformSetInter1 = 3;
    static final int kTransformSetInter2 = 4;
    static final int kTransformSetInter3 = 5;
    static final int kNumTransformSets = 6;
    static final int kTransformClass2D = 0;
    static final int kTransformClassHorizontal = 1;
    static final int kTransformClassVertical = 2;
    static final int kNumTransformClasses = 3;
    static final int kFilterIntraPredictorDc = 0;
    static final int kFilterIntraPredictorVertical = 1;
    static final int kFilterIntraPredictorHorizontal = 2;
    static final int kFilterIntraPredictorD157 = 3;
    static final int kFilterIntraPredictorPaeth = 4;
    static final int kNumFilterIntraPredictors = 5;
    static final int kObmcDirectionVertical = 0;
    static final int kObmcDirectionHorizontal = 1;
    static final int kNumObmcDirections = 2;
    static final int kLoopFilterTypeVertical = 0;
    static final int kLoopFilterTypeHorizontal = 1;
    static final int kNumLoopFilterTypes = 2;
    static final int kLoopFilterTransformSizeId4x4 = 0;
    static final int kLoopFilterTransformSizeId8x8 = 1;
    static final int kLoopFilterTransformSizeId16x16 = 2;
    static final int kNumLoopFilterTransformSizeIds = 3;
    static final int kLoopRestorationTypeNone = 0;
    static final int kLoopRestorationTypeSwitchable = 1;
    static final int kLoopRestorationTypeWiener = 2;
    static final int kLoopRestorationTypeSgrProj = 3;
    static final int kNumLoopRestorationTypes = 4;
    static final int kCompoundReferenceUnidirectional = 0;
    static final int kCompoundReferenceBidirectional = 1;
    static final int kNumCompoundReferenceTypes = 2;
    static final int kCompoundPredictionTypeWedge = 0;
    static final int kCompoundPredictionTypeDiffWeighted = 1;
    static final int kCompoundPredictionTypeAverage = 2;
    static final int kCompoundPredictionTypeIntra = 3;
    static final int kCompoundPredictionTypeDistance = 4;
    static final int kNumCompoundPredictionTypes = 5;
    static final int kNumExplicitCompoundPredictionTypes = 2;
    static final int kInterpolationFilterEightTap = 0;
    static final int kInterpolationFilterEightTapSmooth = 1;
    static final int kInterpolationFilterEightTapSharp = 2;
    static final int kInterpolationFilterBilinear = 3;
    static final int kInterpolationFilterSwitchable = 4;
    static final int kNumInterpolationFilters = 5;
    static final int kNumExplicitInterpolationFilters = 3;
    static final int kMvJointTypeZero = 0;
    static final int kMvJointTypeHorizontalNonZeroVerticalZero = 1;
    static final int kMvJointTypeHorizontalZeroVerticalNonZero = 2;
    static final int kMvJointTypeNonZero = 3;
    static final int kNumMvJointTypes = 4;
    static final int kObuInvalid = -1;
    static final int kObuSequenceHeader = 1;
    static final int kObuTemporalDelimiter = 2;
    static final int kObuFrameHeader = 3;
    static final int kObuTileGroup = 4;
    static final int kObuMetadata = 5;
    static final int kObuFrame = 6;
    static final int kObuRedundantFrameHeader = 7;
    static final int kObuTileList = 8;
    static final int kObuPadding = 15;
    static final BitMaskSet kPredictionModeSmoothMask = new BitMaskSet(9, 11, 10);
    static final int kStatusOk = 0;
    static final int kStatusUnknownError = -1;
    static final int kStatusInvalidArgument = -2;
    static final int kStatusOutOfMemory = -3;
    static final int kStatusResourceExhausted = -4;
    static final int kStatusNotInitialized = -5;
    static final int kStatusAlready = -6;
    static final int kStatusUnimplemented = -7;
    static final int kStatusInternalError = -8;
    static final int kStatusBitstreamError = -9;
    static final int kStatusTryAgain = -10;
    static final int kStatusNothingToDequeue = -11;
    static final int kStatusReservedForFutureExpansionUseDefaultInSwitchInstead_ = -1000;
    static final int[] k4x4WidthLog2 = new int[]{0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5};
    static final int[] k4x4HeightLog2 = new int[]{0, 1, 2, 0, 1, 2, 3, 0, 1, 2, 3, 4, 1, 2, 3, 4, 2, 3, 4, 5, 4, 5};
    static final int[] kNum4x4BlocksWide = new int[]{1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 16, 32, 32};
    static final int[] kNum4x4BlocksHigh = new int[]{1, 2, 4, 1, 2, 4, 8, 1, 2, 4, 8, 16, 2, 4, 8, 16, 4, 8, 16, 32, 16, 32};
    static final int[] kBlockWidthPixels = new int[]{4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 16, 16, 32, 32, 32, 32, 64, 64, 64, 64, 128, 128};
    static final int[] kBlockHeightPixels = new int[]{4, 8, 16, 4, 8, 16, 32, 4, 8, 16, 32, 64, 8, 16, 32, 64, 16, 32, 64, 128, 64, 128};
    static final int[][] kSubSize = new int[][]{{0, 23, 23, 23, 4, 23, 23, 23, 23, 9, 23, 23, 23, 23, 14, 23, 23, 23, 18, 23, 23, 21}, {23, 23, 23, 23, 3, 23, 23, 23, 23, 8, 23, 23, 23, 23, 13, 23, 23, 23, 17, 23, 23, 20}, {23, 23, 23, 23, 1, 23, 23, 23, 23, 5, 23, 23, 23, 23, 10, 23, 23, 23, 15, 23, 23, 19}, {23, 23, 23, 23, 0, 23, 23, 23, 23, 4, 23, 23, 23, 23, 9, 23, 23, 23, 14, 23, 23, 18}, {23, 23, 23, 23, 3, 23, 23, 23, 23, 8, 23, 23, 23, 23, 13, 23, 23, 23, 17, 23, 23, 20}, {23, 23, 23, 23, 3, 23, 23, 23, 23, 8, 23, 23, 23, 23, 13, 23, 23, 23, 17, 23, 23, 20}, {23, 23, 23, 23, 1, 23, 23, 23, 23, 5, 23, 23, 23, 23, 10, 23, 23, 23, 15, 23, 23, 19}, {23, 23, 23, 23, 1, 23, 23, 23, 23, 5, 23, 23, 23, 23, 10, 23, 23, 23, 15, 23, 23, 19}, {23, 23, 23, 23, 23, 23, 23, 23, 23, 7, 23, 23, 23, 23, 12, 23, 23, 23, 16, 23, 23, 23}, {23, 23, 23, 23, 23, 23, 23, 23, 23, 2, 23, 23, 23, 23, 6, 23, 23, 23, 11, 23, 23, 23}};
    static final int[][][] kPlaneResidualSize = new int[][][]{new int[][]{{0, 0}, {0, 0}}, new int[][]{{1, 0}, {23, 0}}, new int[][]{{2, 1}, {23, 1}}, new int[][]{{3, 23}, {0, 0}}, new int[][]{{4, 3}, {1, 0}}, new int[][]{{5, 4}, {23, 1}}, new int[][]{{6, 5}, {23, 2}}, new int[][]{{7, 23}, {3, 3}}, new int[][]{{8, 23}, {4, 3}}, new int[][]{{9, 8}, {5, 4}}, new int[][]{{10, 9}, {23, 5}}, new int[][]{{11, 10}, {23, 6}}, new int[][]{{12, 23}, {8, 7}}, new int[][]{{13, 23}, {9, 8}}, new int[][]{{14, 13}, {10, 9}}, new int[][]{{15, 14}, {23, 10}}, new int[][]{{16, 23}, {13, 12}}, new int[][]{{17, 23}, {14, 13}}, new int[][]{{18, 17}, {15, 14}}, new int[][]{{19, 18}, {23, 15}}, new int[][]{{20, 23}, {18, 17}}, new int[][]{{21, 20}, {19, 18}}};
    static final int[] kProjectionMvDivisionLookup = new int[]{0, 16384, 8192, 5461, 4096, 3276, 2730, 2340, 2048, 1820, 1638, 1489, 1365, 1260, 1170, 1092, 1024, 963, 910, 862, 819, 780, 744, 712, 682, 655, 630, 606, 585, 564, 546, 528};
    static final int[] kTransformWidth = new int[]{4, 4, 4, 8, 8, 8, 8, 16, 16, 16, 16, 16, 32, 32, 32, 32, 64, 64, 64};
    static final int[] kTransformHeight = new int[]{4, 8, 16, 4, 8, 16, 32, 4, 8, 16, 32, 64, 8, 16, 32, 64, 16, 32, 64};
    static final int[] kTransformWidth4x4 = new int[]{1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 8, 8, 8, 8, 16, 16, 16};
    static final int[] kTransformHeight4x4 = new int[]{1, 2, 4, 1, 2, 4, 8, 1, 2, 4, 8, 16, 2, 4, 8, 16, 4, 8, 16};
    static final int[] kTransformWidthLog2 = new int[]{2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6};
    static final int[] kTransformHeightLog2 = new int[]{2, 3, 4, 2, 3, 4, 5, 2, 3, 4, 5, 6, 3, 4, 5, 6, 4, 5, 6};
    static final int[] kSplitTransformSize = new int[]{0, 0, 1, 0, 0, 4, 5, 3, 4, 4, 9, 10, 8, 9, 9, 14, 13, 14, 14};
    static final int[] kTransformSizeSquareMin = new int[]{0, 0, 0, 0, 4, 4, 4, 0, 4, 9, 9, 9, 4, 9, 14, 14, 9, 14, 18};
    static final int[] kTransformSizeSquareMax = new int[]{0, 4, 9, 4, 4, 9, 14, 9, 9, 9, 14, 18, 14, 14, 14, 18, 18, 18, 18};
    static final int[] kNumTransformTypesInSet = new int[]{1, 7, 5, 16, 12, 2};
    static final int[][] kSgrProjParams = new int[][]{{2, 12, 1, 4}, {2, 15, 1, 6}, {2, 18, 1, 8}, {2, 21, 1, 9}, {2, 24, 1, 10}, {2, 29, 1, 11}, {2, 36, 1, 12}, {2, 45, 1, 13}, {2, 56, 1, 14}, {2, 68, 1, 15}, {0, 0, 1, 5}, {0, 0, 1, 8}, {0, 0, 1, 11}, {0, 0, 1, 14}, {2, 30, 0, 0}, {2, 75, 0, 0}};
    static final int[] kSgrProjMultiplierMin = new int[]{-96, -32};
    static final int[] kSgrProjMultiplierMax = new int[]{31, 95};
    static final int[] kWienerTapsMin = new int[]{-5, -23, -17};
    static final int[] kWienerTapsMax = new int[]{10, 8, 46};
    static final int[][] kUpscaleFilterUnsigned = new int[][]{{0, 0, 0, 128, 0, 0, 0, 0}, {0, 0, 1, 128, 2, 1, 0, 0}, {0, 1, 3, 127, 4, 2, 1, 0}, {0, 1, 4, 127, 6, 3, 1, 0}, {0, 2, 6, 126, 8, 3, 1, 0}, {0, 2, 7, 125, 11, 4, 1, 0}, {1, 2, 8, 125, 13, 5, 2, 0}, {1, 3, 9, 124, 15, 6, 2, 0}, {1, 3, 10, 123, 18, 6, 2, 1}, {1, 3, 11, 122, 20, 7, 3, 1}, {1, 4, 12, 121, 22, 8, 3, 1}, {1, 4, 13, 120, 25, 9, 3, 1}, {1, 4, 14, 118, 28, 9, 3, 1}, {1, 4, 15, 117, 30, 10, 4, 1}, {1, 5, 16, 116, 32, 11, 4, 1}, {1, 5, 16, 114, 35, 12, 4, 1}, {1, 5, 17, 112, 38, 12, 4, 1}, {1, 5, 18, 111, 40, 13, 5, 1}, {1, 5, 18, 109, 43, 14, 5, 1}, {1, 6, 19, 107, 45, 14, 5, 1}, {1, 6, 19, 105, 48, 15, 5, 1}, {1, 6, 19, 103, 51, 16, 5, 1}, {1, 6, 20, 101, 53, 16, 6, 1}, {1, 6, 20, 99, 56, 17, 6, 1}, {1, 6, 20, 97, 58, 17, 6, 1}, {1, 6, 20, 95, 61, 18, 6, 1}, {2, 7, 20, 93, 64, 18, 6, 2}, {2, 7, 20, 91, 66, 19, 6, 1}, {2, 7, 20, 88, 69, 19, 6, 1}, {2, 7, 20, 86, 71, 19, 6, 1}, {2, 7, 20, 84, 74, 20, 7, 2}, {2, 7, 20, 81, 76, 20, 7, 1}, {2, 7, 20, 79, 79, 20, 7, 2}, {1, 7, 20, 76, 81, 20, 7, 2}, {2, 7, 20, 74, 84, 20, 7, 2}, {1, 6, 19, 71, 86, 20, 7, 2}, {1, 6, 19, 69, 88, 20, 7, 2}, {1, 6, 19, 66, 91, 20, 7, 2}, {2, 6, 18, 64, 93, 20, 7, 2}, {1, 6, 18, 61, 95, 20, 6, 1}, {1, 6, 17, 58, 97, 20, 6, 1}, {1, 6, 17, 56, 99, 20, 6, 1}, {1, 6, 16, 53, 101, 20, 6, 1}, {1, 5, 16, 51, 103, 19, 6, 1}, {1, 5, 15, 48, 105, 19, 6, 1}, {1, 5, 14, 45, 107, 19, 6, 1}, {1, 5, 14, 43, 109, 18, 5, 1}, {1, 5, 13, 40, 111, 18, 5, 1}, {1, 4, 12, 38, 112, 17, 5, 1}, {1, 4, 12, 35, 114, 16, 5, 1}, {1, 4, 11, 32, 116, 16, 5, 1}, {1, 4, 10, 30, 117, 15, 4, 1}, {1, 3, 9, 28, 118, 14, 4, 1}, {1, 3, 9, 25, 120, 13, 4, 1}, {1, 3, 8, 22, 121, 12, 4, 1}, {1, 3, 7, 20, 122, 11, 3, 1}, {1, 2, 6, 18, 123, 10, 3, 1}, {0, 2, 6, 15, 124, 9, 3, 1}, {0, 2, 5, 13, 125, 8, 2, 1}, {0, 1, 4, 11, 125, 7, 2, 0}, {0, 1, 3, 8, 126, 6, 2, 0}, {0, 1, 3, 6, 127, 4, 1, 0}, {0, 1, 2, 4, 127, 3, 1, 0}, {0, 0, 1, 2, 128, 1, 0, 0}};
    static final int[][] kWarpedFilters8 = new int[][]{{0, 0, 127, 1, 0, 0, 0, 0}, {0, -1, 127, 2, 0, 0, 0, 0}, {1, -3, 127, 4, -1, 0, 0, 0}, {1, -4, 126, 6, -2, 1, 0, 0}, {1, -5, 126, 8, -3, 1, 0, 0}, {1, -6, 125, 11, -4, 1, 0, 0}, {1, -7, 124, 13, -4, 1, 0, 0}, {2, -8, 123, 15, -5, 1, 0, 0}, {2, -9, 122, 18, -6, 1, 0, 0}, {2, -10, 121, 20, -6, 1, 0, 0}, {2, -11, 120, 22, -7, 2, 0, 0}, {2, -12, 119, 25, -8, 2, 0, 0}, {3, -13, 117, 27, -8, 2, 0, 0}, {3, -13, 116, 29, -9, 2, 0, 0}, {3, -14, 114, 32, -10, 3, 0, 0}, {3, -15, 113, 35, -10, 2, 0, 0}, {3, -15, 111, 37, -11, 3, 0, 0}, {3, -16, 109, 40, -11, 3, 0, 0}, {3, -16, 108, 42, -12, 3, 0, 0}, {4, -17, 106, 45, -13, 3, 0, 0}, {4, -17, 104, 47, -13, 3, 0, 0}, {4, -17, 102, 50, -14, 3, 0, 0}, {4, -17, 100, 52, -14, 3, 0, 0}, {4, -18, 98, 55, -15, 4, 0, 0}, {4, -18, 96, 58, -15, 3, 0, 0}, {4, -18, 94, 60, -16, 4, 0, 0}, {4, -18, 91, 63, -16, 4, 0, 0}, {4, -18, 89, 65, -16, 4, 0, 0}, {4, -18, 87, 68, -17, 4, 0, 0}, {4, -18, 85, 70, -17, 4, 0, 0}, {4, -18, 82, 73, -17, 4, 0, 0}, {4, -18, 80, 75, -17, 4, 0, 0}, {4, -18, 78, 78, -18, 4, 0, 0}, {4, -17, 75, 80, -18, 4, 0, 0}, {4, -17, 73, 82, -18, 4, 0, 0}, {4, -17, 70, 85, -18, 4, 0, 0}, {4, -17, 68, 87, -18, 4, 0, 0}, {4, -16, 65, 89, -18, 4, 0, 0}, {4, -16, 63, 91, -18, 4, 0, 0}, {4, -16, 60, 94, -18, 4, 0, 0}, {3, -15, 58, 96, -18, 4, 0, 0}, {4, -15, 55, 98, -18, 4, 0, 0}, {3, -14, 52, 100, -17, 4, 0, 0}, {3, -14, 50, 102, -17, 4, 0, 0}, {3, -13, 47, 104, -17, 4, 0, 0}, {3, -13, 45, 106, -17, 4, 0, 0}, {3, -12, 42, 108, -16, 3, 0, 0}, {3, -11, 40, 109, -16, 3, 0, 0}, {3, -11, 37, 111, -15, 3, 0, 0}, {2, -10, 35, 113, -15, 3, 0, 0}, {3, -10, 32, 114, -14, 3, 0, 0}, {2, -9, 29, 116, -13, 3, 0, 0}, {2, -8, 27, 117, -13, 3, 0, 0}, {2, -8, 25, 119, -12, 2, 0, 0}, {2, -7, 22, 120, -11, 2, 0, 0}, {1, -6, 20, 121, -10, 2, 0, 0}, {1, -6, 18, 122, -9, 2, 0, 0}, {1, -5, 15, 123, -8, 2, 0, 0}, {1, -4, 13, 124, -7, 1, 0, 0}, {1, -4, 11, 125, -6, 1, 0, 0}, {1, -3, 8, 126, -5, 1, 0, 0}, {1, -2, 6, 126, -4, 1, 0, 0}, {0, -1, 4, 127, -3, 1, 0, 0}, {0, 0, 2, 127, -1, 0, 0, 0}, {0, 0, 0, 127, 1, 0, 0, 0}, {0, 0, -1, 127, 2, 0, 0, 0}, {0, 1, -3, 127, 4, -2, 1, 0}, {0, 1, -5, 127, 6, -2, 1, 0}, {0, 2, -6, 126, 8, -3, 1, 0}, {-1, 2, -7, 126, 11, -4, 2, -1}, {-1, 3, -8, 125, 13, -5, 2, -1}, {-1, 3, -10, 124, 16, -6, 3, -1}, {-1, 4, -11, 123, 18, -7, 3, -1}, {-1, 4, -12, 122, 20, -7, 3, -1}, {-1, 4, -13, 121, 23, -8, 3, -1}, {-2, 5, -14, 120, 25, -9, 4, -1}, {-1, 5, -15, 119, 27, -10, 4, -1}, {-1, 5, -16, 118, 30, -11, 4, -1}, {-2, 6, -17, 116, 33, -12, 5, -1}, {-2, 6, -17, 114, 35, -12, 5, -1}, {-2, 6, -18, 113, 38, -13, 5, -1}, {-2, 7, -19, 111, 41, -14, 6, -2}, {-2, 7, -19, 110, 43, -15, 6, -2}, {-2, 7, -20, 108, 46, -15, 6, -2}, {-2, 7, -20, 106, 49, -16, 6, -2}, {-2, 7, -21, 104, 51, -16, 7, -2}, {-2, 7, -21, 102, 54, -17, 7, -2}, {-2, 8, -21, 100, 56, -18, 7, -2}, {-2, 8, -22, 98, 59, -18, 7, -2}, {-2, 8, -22, 96, 62, -19, 7, -2}, {-2, 8, -22, 94, 64, -19, 7, -2}, {-2, 8, -22, 91, 67, -20, 8, -2}, {-2, 8, -22, 89, 69, -20, 8, -2}, {-2, 8, -22, 87, 72, -21, 8, -2}, {-2, 8, -21, 84, 74, -21, 8, -2}, {-2, 8, -22, 82, 77, -21, 8, -2}, {-2, 8, -21, 79, 79, -21, 8, -2}, {-2, 8, -21, 77, 82, -22, 8, -2}, {-2, 8, -21, 74, 84, -21, 8, -2}, {-2, 8, -21, 72, 87, -22, 8, -2}, {-2, 8, -20, 69, 89, -22, 8, -2}, {-2, 8, -20, 67, 91, -22, 8, -2}, {-2, 7, -19, 64, 94, -22, 8, -2}, {-2, 7, -19, 62, 96, -22, 8, -2}, {-2, 7, -18, 59, 98, -22, 8, -2}, {-2, 7, -18, 56, 100, -21, 8, -2}, {-2, 7, -17, 54, 102, -21, 7, -2}, {-2, 7, -16, 51, 104, -21, 7, -2}, {-2, 6, -16, 49, 106, -20, 7, -2}, {-2, 6, -15, 46, 108, -20, 7, -2}, {-2, 6, -15, 43, 110, -19, 7, -2}, {-2, 6, -14, 41, 111, -19, 7, -2}, {-1, 5, -13, 38, 113, -18, 6, -2}, {-1, 5, -12, 35, 114, -17, 6, -2}, {-1, 5, -12, 33, 116, -17, 6, -2}, {-1, 4, -11, 30, 118, -16, 5, -1}, {-1, 4, -10, 27, 119, -15, 5, -1}, {-1, 4, -9, 25, 120, -14, 5, -2}, {-1, 3, -8, 23, 121, -13, 4, -1}, {-1, 3, -7, 20, 122, -12, 4, -1}, {-1, 3, -7, 18, 123, -11, 4, -1}, {-1, 3, -6, 16, 124, -10, 3, -1}, {-1, 2, -5, 13, 125, -8, 3, -1}, {-1, 2, -4, 11, 126, -7, 2, -1}, {0, 1, -3, 8, 126, -6, 2, 0}, {0, 1, -2, 6, 127, -5, 1, 0}, {0, 1, -2, 4, 127, -3, 1, 0}, {0, 0, 0, 2, 127, -1, 0, 0}, {0, 0, 0, 1, 127, 0, 0, 0}, {0, 0, 0, -1, 127, 2, 0, 0}, {0, 0, 1, -3, 127, 4, -1, 0}, {0, 0, 1, -4, 126, 6, -2, 1}, {0, 0, 1, -5, 126, 8, -3, 1}, {0, 0, 1, -6, 125, 11, -4, 1}, {0, 0, 1, -7, 124, 13, -4, 1}, {0, 0, 2, -8, 123, 15, -5, 1}, {0, 0, 2, -9, 122, 18, -6, 1}, {0, 0, 2, -10, 121, 20, -6, 1}, {0, 0, 2, -11, 120, 22, -7, 2}, {0, 0, 2, -12, 119, 25, -8, 2}, {0, 0, 3, -13, 117, 27, -8, 2}, {0, 0, 3, -13, 116, 29, -9, 2}, {0, 0, 3, -14, 114, 32, -10, 3}, {0, 0, 3, -15, 113, 35, -10, 2}, {0, 0, 3, -15, 111, 37, -11, 3}, {0, 0, 3, -16, 109, 40, -11, 3}, {0, 0, 3, -16, 108, 42, -12, 3}, {0, 0, 4, -17, 106, 45, -13, 3}, {0, 0, 4, -17, 104, 47, -13, 3}, {0, 0, 4, -17, 102, 50, -14, 3}, {0, 0, 4, -17, 100, 52, -14, 3}, {0, 0, 4, -18, 98, 55, -15, 4}, {0, 0, 4, -18, 96, 58, -15, 3}, {0, 0, 4, -18, 94, 60, -16, 4}, {0, 0, 4, -18, 91, 63, -16, 4}, {0, 0, 4, -18, 89, 65, -16, 4}, {0, 0, 4, -18, 87, 68, -17, 4}, {0, 0, 4, -18, 85, 70, -17, 4}, {0, 0, 4, -18, 82, 73, -17, 4}, {0, 0, 4, -18, 80, 75, -17, 4}, {0, 0, 4, -18, 78, 78, -18, 4}, {0, 0, 4, -17, 75, 80, -18, 4}, {0, 0, 4, -17, 73, 82, -18, 4}, {0, 0, 4, -17, 70, 85, -18, 4}, {0, 0, 4, -17, 68, 87, -18, 4}, {0, 0, 4, -16, 65, 89, -18, 4}, {0, 0, 4, -16, 63, 91, -18, 4}, {0, 0, 4, -16, 60, 94, -18, 4}, {0, 0, 3, -15, 58, 96, -18, 4}, {0, 0, 4, -15, 55, 98, -18, 4}, {0, 0, 3, -14, 52, 100, -17, 4}, {0, 0, 3, -14, 50, 102, -17, 4}, {0, 0, 3, -13, 47, 104, -17, 4}, {0, 0, 3, -13, 45, 106, -17, 4}, {0, 0, 3, -12, 42, 108, -16, 3}, {0, 0, 3, -11, 40, 109, -16, 3}, {0, 0, 3, -11, 37, 111, -15, 3}, {0, 0, 2, -10, 35, 113, -15, 3}, {0, 0, 3, -10, 32, 114, -14, 3}, {0, 0, 2, -9, 29, 116, -13, 3}, {0, 0, 2, -8, 27, 117, -13, 3}, {0, 0, 2, -8, 25, 119, -12, 2}, {0, 0, 2, -7, 22, 120, -11, 2}, {0, 0, 1, -6, 20, 121, -10, 2}, {0, 0, 1, -6, 18, 122, -9, 2}, {0, 0, 1, -5, 15, 123, -8, 2}, {0, 0, 1, -4, 13, 124, -7, 1}, {0, 0, 1, -4, 11, 125, -6, 1}, {0, 0, 1, -3, 8, 126, -5, 1}, {0, 0, 1, -2, 6, 126, -4, 1}, {0, 0, 0, -1, 4, 127, -3, 1}, {0, 0, 0, 0, 2, 127, -1, 0}, {0, 0, 0, 0, 2, 127, -1, 0}};
    static final int[][] kWarpedFilters = new int[][]{{0, 0, 127, 1, 0, 0, 0, 0}, {0, -1, 127, 2, 0, 0, 0, 0}, {1, -3, 127, 4, -1, 0, 0, 0}, {1, -4, 126, 6, -2, 1, 0, 0}, {1, -5, 126, 8, -3, 1, 0, 0}, {1, -6, 125, 11, -4, 1, 0, 0}, {1, -7, 124, 13, -4, 1, 0, 0}, {2, -8, 123, 15, -5, 1, 0, 0}, {2, -9, 122, 18, -6, 1, 0, 0}, {2, -10, 121, 20, -6, 1, 0, 0}, {2, -11, 120, 22, -7, 2, 0, 0}, {2, -12, 119, 25, -8, 2, 0, 0}, {3, -13, 117, 27, -8, 2, 0, 0}, {3, -13, 116, 29, -9, 2, 0, 0}, {3, -14, 114, 32, -10, 3, 0, 0}, {3, -15, 113, 35, -10, 2, 0, 0}, {3, -15, 111, 37, -11, 3, 0, 0}, {3, -16, 109, 40, -11, 3, 0, 0}, {3, -16, 108, 42, -12, 3, 0, 0}, {4, -17, 106, 45, -13, 3, 0, 0}, {4, -17, 104, 47, -13, 3, 0, 0}, {4, -17, 102, 50, -14, 3, 0, 0}, {4, -17, 100, 52, -14, 3, 0, 0}, {4, -18, 98, 55, -15, 4, 0, 0}, {4, -18, 96, 58, -15, 3, 0, 0}, {4, -18, 94, 60, -16, 4, 0, 0}, {4, -18, 91, 63, -16, 4, 0, 0}, {4, -18, 89, 65, -16, 4, 0, 0}, {4, -18, 87, 68, -17, 4, 0, 0}, {4, -18, 85, 70, -17, 4, 0, 0}, {4, -18, 82, 73, -17, 4, 0, 0}, {4, -18, 80, 75, -17, 4, 0, 0}, {4, -18, 78, 78, -18, 4, 0, 0}, {4, -17, 75, 80, -18, 4, 0, 0}, {4, -17, 73, 82, -18, 4, 0, 0}, {4, -17, 70, 85, -18, 4, 0, 0}, {4, -17, 68, 87, -18, 4, 0, 0}, {4, -16, 65, 89, -18, 4, 0, 0}, {4, -16, 63, 91, -18, 4, 0, 0}, {4, -16, 60, 94, -18, 4, 0, 0}, {3, -15, 58, 96, -18, 4, 0, 0}, {4, -15, 55, 98, -18, 4, 0, 0}, {3, -14, 52, 100, -17, 4, 0, 0}, {3, -14, 50, 102, -17, 4, 0, 0}, {3, -13, 47, 104, -17, 4, 0, 0}, {3, -13, 45, 106, -17, 4, 0, 0}, {3, -12, 42, 108, -16, 3, 0, 0}, {3, -11, 40, 109, -16, 3, 0, 0}, {3, -11, 37, 111, -15, 3, 0, 0}, {2, -10, 35, 113, -15, 3, 0, 0}, {3, -10, 32, 114, -14, 3, 0, 0}, {2, -9, 29, 116, -13, 3, 0, 0}, {2, -8, 27, 117, -13, 3, 0, 0}, {2, -8, 25, 119, -12, 2, 0, 0}, {2, -7, 22, 120, -11, 2, 0, 0}, {1, -6, 20, 121, -10, 2, 0, 0}, {1, -6, 18, 122, -9, 2, 0, 0}, {1, -5, 15, 123, -8, 2, 0, 0}, {1, -4, 13, 124, -7, 1, 0, 0}, {1, -4, 11, 125, -6, 1, 0, 0}, {1, -3, 8, 126, -5, 1, 0, 0}, {1, -2, 6, 126, -4, 1, 0, 0}, {0, -1, 4, 127, -3, 1, 0, 0}, {0, 0, 2, 127, -1, 0, 0, 0}, {0, 0, 0, 127, 1, 0, 0, 0}, {0, 0, -1, 127, 2, 0, 0, 0}, {0, 1, -3, 127, 4, -2, 1, 0}, {0, 1, -5, 127, 6, -2, 1, 0}, {0, 2, -6, 126, 8, -3, 1, 0}, {-1, 2, -7, 126, 11, -4, 2, -1}, {-1, 3, -8, 125, 13, -5, 2, -1}, {-1, 3, -10, 124, 16, -6, 3, -1}, {-1, 4, -11, 123, 18, -7, 3, -1}, {-1, 4, -12, 122, 20, -7, 3, -1}, {-1, 4, -13, 121, 23, -8, 3, -1}, {-2, 5, -14, 120, 25, -9, 4, -1}, {-1, 5, -15, 119, 27, -10, 4, -1}, {-1, 5, -16, 118, 30, -11, 4, -1}, {-2, 6, -17, 116, 33, -12, 5, -1}, {-2, 6, -17, 114, 35, -12, 5, -1}, {-2, 6, -18, 113, 38, -13, 5, -1}, {-2, 7, -19, 111, 41, -14, 6, -2}, {-2, 7, -19, 110, 43, -15, 6, -2}, {-2, 7, -20, 108, 46, -15, 6, -2}, {-2, 7, -20, 106, 49, -16, 6, -2}, {-2, 7, -21, 104, 51, -16, 7, -2}, {-2, 7, -21, 102, 54, -17, 7, -2}, {-2, 8, -21, 100, 56, -18, 7, -2}, {-2, 8, -22, 98, 59, -18, 7, -2}, {-2, 8, -22, 96, 62, -19, 7, -2}, {-2, 8, -22, 94, 64, -19, 7, -2}, {-2, 8, -22, 91, 67, -20, 8, -2}, {-2, 8, -22, 89, 69, -20, 8, -2}, {-2, 8, -22, 87, 72, -21, 8, -2}, {-2, 8, -21, 84, 74, -21, 8, -2}, {-2, 8, -22, 82, 77, -21, 8, -2}, {-2, 8, -21, 79, 79, -21, 8, -2}, {-2, 8, -21, 77, 82, -22, 8, -2}, {-2, 8, -21, 74, 84, -21, 8, -2}, {-2, 8, -21, 72, 87, -22, 8, -2}, {-2, 8, -20, 69, 89, -22, 8, -2}, {-2, 8, -20, 67, 91, -22, 8, -2}, {-2, 7, -19, 64, 94, -22, 8, -2}, {-2, 7, -19, 62, 96, -22, 8, -2}, {-2, 7, -18, 59, 98, -22, 8, -2}, {-2, 7, -18, 56, 100, -21, 8, -2}, {-2, 7, -17, 54, 102, -21, 7, -2}, {-2, 7, -16, 51, 104, -21, 7, -2}, {-2, 6, -16, 49, 106, -20, 7, -2}, {-2, 6, -15, 46, 108, -20, 7, -2}, {-2, 6, -15, 43, 110, -19, 7, -2}, {-2, 6, -14, 41, 111, -19, 7, -2}, {-1, 5, -13, 38, 113, -18, 6, -2}, {-1, 5, -12, 35, 114, -17, 6, -2}, {-1, 5, -12, 33, 116, -17, 6, -2}, {-1, 4, -11, 30, 118, -16, 5, -1}, {-1, 4, -10, 27, 119, -15, 5, -1}, {-1, 4, -9, 25, 120, -14, 5, -2}, {-1, 3, -8, 23, 121, -13, 4, -1}, {-1, 3, -7, 20, 122, -12, 4, -1}, {-1, 3, -7, 18, 123, -11, 4, -1}, {-1, 3, -6, 16, 124, -10, 3, -1}, {-1, 2, -5, 13, 125, -8, 3, -1}, {-1, 2, -4, 11, 126, -7, 2, -1}, {0, 1, -3, 8, 126, -6, 2, 0}, {0, 1, -2, 6, 127, -5, 1, 0}, {0, 1, -2, 4, 127, -3, 1, 0}, {0, 0, 0, 2, 127, -1, 0, 0}, {0, 0, 0, 1, 127, 0, 0, 0}, {0, 0, 0, -1, 127, 2, 0, 0}, {0, 0, 1, -3, 127, 4, -1, 0}, {0, 0, 1, -4, 126, 6, -2, 1}, {0, 0, 1, -5, 126, 8, -3, 1}, {0, 0, 1, -6, 125, 11, -4, 1}, {0, 0, 1, -7, 124, 13, -4, 1}, {0, 0, 2, -8, 123, 15, -5, 1}, {0, 0, 2, -9, 122, 18, -6, 1}, {0, 0, 2, -10, 121, 20, -6, 1}, {0, 0, 2, -11, 120, 22, -7, 2}, {0, 0, 2, -12, 119, 25, -8, 2}, {0, 0, 3, -13, 117, 27, -8, 2}, {0, 0, 3, -13, 116, 29, -9, 2}, {0, 0, 3, -14, 114, 32, -10, 3}, {0, 0, 3, -15, 113, 35, -10, 2}, {0, 0, 3, -15, 111, 37, -11, 3}, {0, 0, 3, -16, 109, 40, -11, 3}, {0, 0, 3, -16, 108, 42, -12, 3}, {0, 0, 4, -17, 106, 45, -13, 3}, {0, 0, 4, -17, 104, 47, -13, 3}, {0, 0, 4, -17, 102, 50, -14, 3}, {0, 0, 4, -17, 100, 52, -14, 3}, {0, 0, 4, -18, 98, 55, -15, 4}, {0, 0, 4, -18, 96, 58, -15, 3}, {0, 0, 4, -18, 94, 60, -16, 4}, {0, 0, 4, -18, 91, 63, -16, 4}, {0, 0, 4, -18, 89, 65, -16, 4}, {0, 0, 4, -18, 87, 68, -17, 4}, {0, 0, 4, -18, 85, 70, -17, 4}, {0, 0, 4, -18, 82, 73, -17, 4}, {0, 0, 4, -18, 80, 75, -17, 4}, {0, 0, 4, -18, 78, 78, -18, 4}, {0, 0, 4, -17, 75, 80, -18, 4}, {0, 0, 4, -17, 73, 82, -18, 4}, {0, 0, 4, -17, 70, 85, -18, 4}, {0, 0, 4, -17, 68, 87, -18, 4}, {0, 0, 4, -16, 65, 89, -18, 4}, {0, 0, 4, -16, 63, 91, -18, 4}, {0, 0, 4, -16, 60, 94, -18, 4}, {0, 0, 3, -15, 58, 96, -18, 4}, {0, 0, 4, -15, 55, 98, -18, 4}, {0, 0, 3, -14, 52, 100, -17, 4}, {0, 0, 3, -14, 50, 102, -17, 4}, {0, 0, 3, -13, 47, 104, -17, 4}, {0, 0, 3, -13, 45, 106, -17, 4}, {0, 0, 3, -12, 42, 108, -16, 3}, {0, 0, 3, -11, 40, 109, -16, 3}, {0, 0, 3, -11, 37, 111, -15, 3}, {0, 0, 2, -10, 35, 113, -15, 3}, {0, 0, 3, -10, 32, 114, -14, 3}, {0, 0, 2, -9, 29, 116, -13, 3}, {0, 0, 2, -8, 27, 117, -13, 3}, {0, 0, 2, -8, 25, 119, -12, 2}, {0, 0, 2, -7, 22, 120, -11, 2}, {0, 0, 1, -6, 20, 121, -10, 2}, {0, 0, 1, -6, 18, 122, -9, 2}, {0, 0, 1, -5, 15, 123, -8, 2}, {0, 0, 1, -4, 13, 124, -7, 1}, {0, 0, 1, -4, 11, 125, -6, 1}, {0, 0, 1, -3, 8, 126, -5, 1}, {0, 0, 1, -2, 6, 126, -4, 1}, {0, 0, 0, -1, 4, 127, -3, 1}, {0, 0, 0, 0, 2, 127, -1, 0}, {0, 0, 0, 0, 2, 127, -1, 0}};
    static final int[][][] kHalfSubPixelFilters = new int[][][]{new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 1, -3, 63, 4, -1, 0, 0}, {0, 1, -5, 61, 9, -2, 0, 0}, {0, 1, -6, 58, 14, -4, 1, 0}, {0, 1, -7, 55, 19, -5, 1, 0}, {0, 1, -7, 51, 24, -6, 1, 0}, {0, 1, -8, 47, 29, -6, 1, 0}, {0, 1, -7, 42, 33, -6, 1, 0}, {0, 1, -7, 38, 38, -7, 1, 0}, {0, 1, -6, 33, 42, -7, 1, 0}, {0, 1, -6, 29, 47, -8, 1, 0}, {0, 1, -6, 24, 51, -7, 1, 0}, {0, 1, -5, 19, 55, -7, 1, 0}, {0, 1, -4, 14, 58, -6, 1, 0}, {0, 0, -2, 9, 61, -5, 1, 0}, {0, 0, -1, 4, 63, -3, 1, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 1, 14, 31, 17, 1, 0, 0}, {0, 0, 13, 31, 18, 2, 0, 0}, {0, 0, 11, 31, 20, 2, 0, 0}, {0, 0, 10, 30, 21, 3, 0, 0}, {0, 0, 9, 29, 22, 4, 0, 0}, {0, 0, 8, 28, 23, 5, 0, 0}, {0, -1, 8, 27, 24, 6, 0, 0}, {0, -1, 7, 26, 26, 7, -1, 0}, {0, 0, 6, 24, 27, 8, -1, 0}, {0, 0, 5, 23, 28, 8, 0, 0}, {0, 0, 4, 22, 29, 9, 0, 0}, {0, 0, 3, 21, 30, 10, 0, 0}, {0, 0, 2, 20, 31, 11, 0, 0}, {0, 0, 2, 18, 31, 13, 0, 0}, {0, 0, 1, 17, 31, 14, 1, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {-1, 1, -3, 63, 4, -1, 1, 0}, {-1, 3, -6, 62, 8, -3, 2, -1}, {-1, 4, -9, 60, 13, -5, 3, -1}, {-2, 5, -11, 58, 19, -7, 3, -1}, {-2, 5, -11, 54, 24, -9, 4, -1}, {-2, 5, -12, 50, 30, -10, 4, -1}, {-2, 5, -12, 45, 35, -11, 5, -1}, {-2, 6, -12, 40, 40, -12, 6, -2}, {-1, 5, -11, 35, 45, -12, 5, -2}, {-1, 4, -10, 30, 50, -12, 5, -2}, {-1, 4, -9, 24, 54, -11, 5, -2}, {-1, 3, -7, 19, 58, -11, 5, -2}, {-1, 3, -5, 13, 60, -9, 4, -1}, {-1, 2, -3, 8, 62, -6, 3, -1}, {0, 1, -1, 4, 63, -3, 1, -1}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, 0, 60, 4, 0, 0, 0}, {0, 0, 0, 56, 8, 0, 0, 0}, {0, 0, 0, 52, 12, 0, 0, 0}, {0, 0, 0, 48, 16, 0, 0, 0}, {0, 0, 0, 44, 20, 0, 0, 0}, {0, 0, 0, 40, 24, 0, 0, 0}, {0, 0, 0, 36, 28, 0, 0, 0}, {0, 0, 0, 32, 32, 0, 0, 0}, {0, 0, 0, 28, 36, 0, 0, 0}, {0, 0, 0, 24, 40, 0, 0, 0}, {0, 0, 0, 20, 44, 0, 0, 0}, {0, 0, 0, 16, 48, 0, 0, 0}, {0, 0, 0, 12, 52, 0, 0, 0}, {0, 0, 0, 8, 56, 0, 0, 0}, {0, 0, 0, 4, 60, 0, 0, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, -2, 63, 4, -1, 0, 0}, {0, 0, -4, 61, 9, -2, 0, 0}, {0, 0, -5, 58, 14, -3, 0, 0}, {0, 0, -6, 55, 19, -4, 0, 0}, {0, 0, -6, 51, 24, -5, 0, 0}, {0, 0, -7, 47, 29, -5, 0, 0}, {0, 0, -6, 42, 33, -5, 0, 0}, {0, 0, -6, 38, 38, -6, 0, 0}, {0, 0, -5, 33, 42, -6, 0, 0}, {0, 0, -5, 29, 47, -7, 0, 0}, {0, 0, -5, 24, 51, -6, 0, 0}, {0, 0, -4, 19, 55, -6, 0, 0}, {0, 0, -3, 14, 58, -5, 0, 0}, {0, 0, -2, 9, 61, -4, 0, 0}, {0, 0, -1, 4, 63, -2, 0, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, 15, 31, 17, 1, 0, 0}, {0, 0, 13, 31, 18, 2, 0, 0}, {0, 0, 11, 31, 20, 2, 0, 0}, {0, 0, 10, 30, 21, 3, 0, 0}, {0, 0, 9, 29, 22, 4, 0, 0}, {0, 0, 8, 28, 23, 5, 0, 0}, {0, 0, 7, 27, 24, 6, 0, 0}, {0, 0, 6, 26, 26, 6, 0, 0}, {0, 0, 6, 24, 27, 7, 0, 0}, {0, 0, 5, 23, 28, 8, 0, 0}, {0, 0, 4, 22, 29, 9, 0, 0}, {0, 0, 3, 21, 30, 10, 0, 0}, {0, 0, 2, 20, 31, 11, 0, 0}, {0, 0, 2, 18, 31, 13, 0, 0}, {0, 0, 1, 17, 31, 15, 0, 0}}};
    static final int[][][] kAbsHalfSubPixelFilters = new int[][][]{new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 1, 3, 63, 4, 1, 0, 0}, {0, 1, 5, 61, 9, 2, 0, 0}, {0, 1, 6, 58, 14, 4, 1, 0}, {0, 1, 7, 55, 19, 5, 1, 0}, {0, 1, 7, 51, 24, 6, 1, 0}, {0, 1, 8, 47, 29, 6, 1, 0}, {0, 1, 7, 42, 33, 6, 1, 0}, {0, 1, 7, 38, 38, 7, 1, 0}, {0, 1, 6, 33, 42, 7, 1, 0}, {0, 1, 6, 29, 47, 8, 1, 0}, {0, 1, 6, 24, 51, 7, 1, 0}, {0, 1, 5, 19, 55, 7, 1, 0}, {0, 1, 4, 14, 58, 6, 1, 0}, {0, 0, 2, 9, 61, 5, 1, 0}, {0, 0, 1, 4, 63, 3, 1, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 1, 14, 31, 17, 1, 0, 0}, {0, 0, 13, 31, 18, 2, 0, 0}, {0, 0, 11, 31, 20, 2, 0, 0}, {0, 0, 10, 30, 21, 3, 0, 0}, {0, 0, 9, 29, 22, 4, 0, 0}, {0, 0, 8, 28, 23, 5, 0, 0}, {0, 1, 8, 27, 24, 6, 0, 0}, {0, 1, 7, 26, 26, 7, 1, 0}, {0, 0, 6, 24, 27, 8, 1, 0}, {0, 0, 5, 23, 28, 8, 0, 0}, {0, 0, 4, 22, 29, 9, 0, 0}, {0, 0, 3, 21, 30, 10, 0, 0}, {0, 0, 2, 20, 31, 11, 0, 0}, {0, 0, 2, 18, 31, 13, 0, 0}, {0, 0, 1, 17, 31, 14, 1, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {1, 1, 3, 63, 4, 1, 1, 0}, {1, 3, 6, 62, 8, 3, 2, 1}, {1, 4, 9, 60, 13, 5, 3, 1}, {2, 5, 11, 58, 19, 7, 3, 1}, {2, 5, 11, 54, 24, 9, 4, 1}, {2, 5, 12, 50, 30, 10, 4, 1}, {2, 5, 12, 45, 35, 11, 5, 1}, {2, 6, 12, 40, 40, 12, 6, 2}, {1, 5, 11, 35, 45, 12, 5, 2}, {1, 4, 10, 30, 50, 12, 5, 2}, {1, 4, 9, 24, 54, 11, 5, 2}, {1, 3, 7, 19, 58, 11, 5, 2}, {1, 3, 5, 13, 60, 9, 4, 1}, {1, 2, 3, 8, 62, 6, 3, 1}, {0, 1, 1, 4, 63, 3, 1, 1}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, 0, 60, 4, 0, 0, 0}, {0, 0, 0, 56, 8, 0, 0, 0}, {0, 0, 0, 52, 12, 0, 0, 0}, {0, 0, 0, 48, 16, 0, 0, 0}, {0, 0, 0, 44, 20, 0, 0, 0}, {0, 0, 0, 40, 24, 0, 0, 0}, {0, 0, 0, 36, 28, 0, 0, 0}, {0, 0, 0, 32, 32, 0, 0, 0}, {0, 0, 0, 28, 36, 0, 0, 0}, {0, 0, 0, 24, 40, 0, 0, 0}, {0, 0, 0, 20, 44, 0, 0, 0}, {0, 0, 0, 16, 48, 0, 0, 0}, {0, 0, 0, 12, 52, 0, 0, 0}, {0, 0, 0, 8, 56, 0, 0, 0}, {0, 0, 0, 4, 60, 0, 0, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, 2, 63, 4, 1, 0, 0}, {0, 0, 4, 61, 9, 2, 0, 0}, {0, 0, 5, 58, 14, 3, 0, 0}, {0, 0, 6, 55, 19, 4, 0, 0}, {0, 0, 6, 51, 24, 5, 0, 0}, {0, 0, 7, 47, 29, 5, 0, 0}, {0, 0, 6, 42, 33, 5, 0, 0}, {0, 0, 6, 38, 38, 6, 0, 0}, {0, 0, 5, 33, 42, 6, 0, 0}, {0, 0, 5, 29, 47, 7, 0, 0}, {0, 0, 5, 24, 51, 6, 0, 0}, {0, 0, 4, 19, 55, 6, 0, 0}, {0, 0, 3, 14, 58, 5, 0, 0}, {0, 0, 2, 9, 61, 4, 0, 0}, {0, 0, 1, 4, 63, 2, 0, 0}}, new int[][]{{0, 0, 0, 64, 0, 0, 0, 0}, {0, 0, 15, 31, 17, 1, 0, 0}, {0, 0, 13, 31, 18, 2, 0, 0}, {0, 0, 11, 31, 20, 2, 0, 0}, {0, 0, 10, 30, 21, 3, 0, 0}, {0, 0, 9, 29, 22, 4, 0, 0}, {0, 0, 8, 28, 23, 5, 0, 0}, {0, 0, 7, 27, 24, 6, 0, 0}, {0, 0, 6, 26, 26, 6, 0, 0}, {0, 0, 6, 24, 27, 7, 0, 0}, {0, 0, 5, 23, 28, 8, 0, 0}, {0, 0, 4, 22, 29, 9, 0, 0}, {0, 0, 3, 21, 30, 10, 0, 0}, {0, 0, 2, 20, 31, 11, 0, 0}, {0, 0, 2, 18, 31, 13, 0, 0}, {0, 0, 1, 17, 31, 15, 0, 0}}};
    static final int[] kDirectionalIntraPredictorDerivative = new int[]{1023, 0, 547, 372, 0, 0, 273, 215, 0, 178, 151, 0, 132, 116, 0, 102, 0, 90, 80, 0, 71, 64, 0, 57, 51, 0, 45, 0, 40, 35, 0, 31, 27, 0, 23, 19, 0, 15, 0, 11, 0, 7, 3};
    static final int[][] kDeblockFilterLevelIndex = new int[][]{{0, 1}, {2, 2}, {3, 3}};
    static final int[][][] kFilterIntraTaps = new int[][][]{new int[][]{{-6, 10, 0, 0, 0, 12, 0, 0}, {-5, 2, 10, 0, 0, 9, 0, 0}, {-3, 1, 1, 10, 0, 7, 0, 0}, {-3, 1, 1, 2, 10, 5, 0, 0}, {-4, 6, 0, 0, 0, 2, 12, 0}, {-3, 2, 6, 0, 0, 2, 9, 0}, {-3, 2, 2, 6, 0, 2, 7, 0}, {-3, 1, 2, 2, 6, 3, 5, 0}}, new int[][]{{-10, 16, 0, 0, 0, 10, 0, 0}, {-6, 0, 16, 0, 0, 6, 0, 0}, {-4, 0, 0, 16, 0, 4, 0, 0}, {-2, 0, 0, 0, 16, 2, 0, 0}, {-10, 16, 0, 0, 0, 0, 10, 0}, {-6, 0, 16, 0, 0, 0, 6, 0}, {-4, 0, 0, 16, 0, 0, 4, 0}, {-2, 0, 0, 0, 16, 0, 2, 0}}, new int[][]{{-8, 8, 0, 0, 0, 16, 0, 0}, {-8, 0, 8, 0, 0, 16, 0, 0}, {-8, 0, 0, 8, 0, 16, 0, 0}, {-8, 0, 0, 0, 8, 16, 0, 0}, {-4, 4, 0, 0, 0, 0, 16, 0}, {-4, 0, 4, 0, 0, 0, 16, 0}, {-4, 0, 0, 4, 0, 0, 16, 0}, {-4, 0, 0, 0, 4, 0, 16, 0}}, new int[][]{{-2, 8, 0, 0, 0, 10, 0, 0}, {-1, 3, 8, 0, 0, 6, 0, 0}, {-1, 2, 3, 8, 0, 4, 0, 0}, {0, 1, 2, 3, 8, 2, 0, 0}, {-1, 4, 0, 0, 0, 3, 10, 0}, {-1, 3, 4, 0, 0, 4, 6, 0}, {-1, 2, 3, 4, 0, 4, 4, 0}, {-1, 2, 2, 3, 4, 3, 3, 0}}, new int[][]{{-12, 14, 0, 0, 0, 14, 0, 0}, {-10, 0, 14, 0, 0, 12, 0, 0}, {-9, 0, 0, 14, 0, 11, 0, 0}, {-8, 0, 0, 0, 14, 10, 0, 0}, {-10, 12, 0, 0, 0, 0, 14, 0}, {-9, 1, 12, 0, 0, 0, 12, 0}, {-8, 0, 0, 12, 0, 1, 11, 0}, {-7, 0, 0, 1, 12, 1, 9, 0}}};
    static final int[][] kSgrScaleParameter = new int[][]{{140, 3236}, {112, 2158}, {93, 1618}, {80, 1438}, {70, 1295}, {58, 1177}, {47, 1079}, {37, 996}, {30, 925}, {25, 863}, {0, 2589}, {0, 1618}, {0, 1177}, {0, 925}, {56, 0}, {22, 0}};
    static final int[][] kCdefPrimaryTaps = new int[][]{{4, 2}, {3, 3}};
    static final int[][][] kCdefDirectionsPadded = new int[][][]{new int[][]{{1, 0}, {2, 0}}, new int[][]{{1, 0}, {2, -1}}, new int[][]{{-1, 1}, {-2, 2}}, new int[][]{{0, 1}, {-1, 2}}, new int[][]{{0, 1}, {0, 2}}, new int[][]{{0, 1}, {1, 2}}, new int[][]{{1, 1}, {2, 2}}, new int[][]{{1, 0}, {2, 1}}, new int[][]{{1, 0}, {2, 0}}, new int[][]{{1, 0}, {2, -1}}, new int[][]{{-1, 1}, {-2, 2}}, new int[][]{{0, 1}, {-1, 2}}};
    static final int kCdefSecondaryTap0 = 2;
    static final int kCdefSecondaryTap1 = 1;
    static final int[] kCdefDivisionTable = new int[]{840, 420, 280, 210, 168, 140, 120, 105, 120, 140, 168, 210, 280, 420, 840, 0};
    static final int[] kCdefDivisionTableOdd = new int[]{420, 210, 140, 0, 140, 210, 420, 0};
    static final int[] kDivisionTable = new int[]{840, 420, 280, 210, 168, 140, 120, 105};
    static final int kCflLumaBufferStride = 32;
    static final int kSubsamplingType444 = 0;
    static final int kSubsamplingType422 = 1;
    static final int kSubsamplingType420 = 2;
    static final int kNumSubsamplingTypes = 3;
    static final int[][][] kCdefDirections = new int[][][]{new int[][]{{-1, 1}, {-2, 2}}, new int[][]{{0, 1}, {-1, 2}}, new int[][]{{0, 1}, {0, 2}}, new int[][]{{0, 1}, {1, 2}}, new int[][]{{1, 1}, {2, 2}}, new int[][]{{1, 0}, {2, 1}}, new int[][]{{1, 0}, {2, 0}}, new int[][]{{1, 0}, {2, -1}}};
    static final int kDeltaQSmall = 3;
    static final int kDeltaLfSmall = 3;
    static final int[] kIntraYModeContext = new int[]{0, 1, 2, 3, 4, 4, 4, 4, 3, 0, 1, 2, 0};
    static final int[] kSizeGroup = new int[]{0, 0, 0, 0, 1, 1, 1, 0, 1, 2, 2, 2, 1, 2, 3, 3, 2, 3, 3, 3, 3, 3};
    static final int kCompoundModeNewMvContexts = 5;
    static final int[][] kCompoundModeContextMap = new int[][]{{0, 1, 1, 1, 1}, {1, 2, 3, 4, 4}, {4, 4, 5, 6, 7}};
    static final int kCflSignZero = 0;
    static final int kCflSignNegative = 1;
    static final int kCflSignPositive = 2;
    static final int[][] kCflAlphaLookup = new int[][]{{0, 1, -2, 0}, {0, 2, -1, 3}, {1, 0, 0, -2}, {1, 1, 1, 1}, {1, 2, 2, 4}, {2, 0, 3, -1}, {2, 1, 4, 2}, {2, 2, 5, 5}};
    static final BitMaskSet kPredictionModeHasNearMvMask = new BitMaskSet(15, 19, 22, 23);
    static final BitMaskSet kIsInterIntraModeAllowedMask = new BitMaskSet(4, 5, 8, 9, 10, 13, 14);
    static final int kBlockDecodedStride = 34;
    static final int kFrameBufferRowAlignment = 16;
    static final int[][] kQuantizedDistanceWeight = new int[][]{{2, 3}, {2, 5}, {2, 7}, {1, 31}};
    static final int[][] kQuantizedDistanceLookup = new int[][]{{9, 7}, {11, 5}, {12, 4}, {13, 3}};
    static final int[] kQuantizationShift = new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 2, 1, 2, 2};
    static final int kMaxBlockWidth4x4 = 32;
    static final int kMaxBlockHeight4x4 = 32;
    static final BitMaskSet kIsWedgeCompoundModeAllowed = new BitMaskSet(4, 5, 6, 8, 9, 10, 12, 13, 14);
    static final int[] kLoopRestorationBorderRows = new int[]{54, 26};
    static final int kProcessingModeParseOnly = 0;
    static final int kProcessingModeDecodeOnly = 1;
    static final int kProcessingModeParseAndDecode = 2;
    static final int kMetadataTypeHdrContentLightLevel = 1;
    static final int kMetadataTypeHdrMasteringDisplayColorVolume = 2;
    static final int kMetadataTypeScalability = 3;
    static final int kMetadataTypeItutT35 = 4;
    static final int kMetadataTypeTimecode = 5;
    static final int kSegmentFeatureQuantizer = 0;
    static final int kSegmentFeatureLoopFilterYVertical = 1;
    static final int kSegmentFeatureLoopFilterYHorizontal = 2;
    static final int kSegmentFeatureLoopFilterU = 3;
    static final int kSegmentFeatureLoopFilterV = 4;
    static final int kSegmentFeatureReferenceFrame = 5;
    static final int kSegmentFeatureSkip = 6;
    static final int kSegmentFeatureGlobalMv = 7;
    static final int kSegmentFeatureMax = 8;
    static final int[] kSegmentationFeatureBits = new int[]{8, 6, 6, 6, 6, 3, 0, 0};
    static final int[] kSegmentationFeatureMaxValues = new int[]{255, 63, 63, 63, 63, 7, 0, 0};
    static final int kMaxVariableTransformTreeDepth = 2;
    static final int[] kTxDepthCdfIndex = new int[]{0, 0, 1, 0, 0, 1, 2, 1, 1, 1, 2, 3, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3};
    static final int[] kMaxTransformSizeRectangle = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 18, 18, 18};
    static final int kQuantizerCoefficientBaseRange = 12;
    static final int kNumQuantizerBaseLevels = 2;
    static final int kCoeffBaseRangeMaxIterations = 4;
    static final int kEntropyContextLeft = 0;
    static final int kEntropyContextTop = 1;
    static final int[][] kAllZeroContextsByTopLeft = new int[][]{{1, 2, 2, 2, 3}, {2, 4, 4, 4, 5}, {2, 4, 4, 4, 5}, {2, 4, 4, 4, 5}, {3, 5, 5, 5, 6}};
    static final int kDfsStackSize = 16;
    static final BitMaskSet[] kTransformTypeInSetMask = new BitMaskSet[]{new BitMaskSet(1), new BitMaskSet(3599), new BitMaskSet(527), new BitMaskSet(65535), new BitMaskSet(4095), new BitMaskSet(513)};
    static final int[] kFilterIntraModeToIntraPredictor = new int[]{0, 1, 2, 6, 0};
    static final BitMaskSet kPredictionModeDeltasMask = new BitMaskSet(14, 15, 17, 18, 19, 20, 21, 22, 23, 25);
    static final int[] kEobMultiSizeLookup = new int[]{0, 1, 2, 1, 2, 3, 4, 2, 3, 4, 5, 5, 4, 5, 6, 6, 5, 6, 6};
    static final int[][][] kCoeffBaseContextOffset = new int[][][]{new int[][]{{0, 1, 6, 6, 0}, {1, 6, 6, 21, 0}, {6, 6, 21, 21, 0}, {6, 21, 21, 21, 0}, {0, 0, 0, 0, 0}}, new int[][]{{0, 11, 11, 11, 0}, {11, 11, 11, 11, 0}, {6, 6, 21, 21, 0}, {6, 21, 21, 21, 0}, {21, 21, 21, 21, 0}}, new int[][]{{0, 11, 11, 11, 0}, {11, 11, 11, 11, 0}, {6, 6, 21, 21, 0}, {6, 21, 21, 21, 0}, {21, 21, 21, 21, 0}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {0, 0, 0, 0, 0}}, new int[][]{{0, 1, 6, 6, 21}, {1, 6, 6, 21, 21}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 11, 11, 11, 11}, {11, 11, 11, 11, 11}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 11, 11, 11, 11}, {11, 11, 11, 11, 11}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {0, 0, 0, 0, 0}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}}, new int[][]{{0, 1, 6, 6, 21}, {1, 6, 6, 21, 21}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 11, 11, 11, 11}, {11, 11, 11, 11, 11}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 11, 11, 11, 11}, {11, 11, 11, 11, 11}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}}, new int[][]{{0, 1, 6, 6, 21}, {1, 6, 6, 21, 21}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 11, 11, 11, 11}, {11, 11, 11, 11, 11}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}}, new int[][]{{0, 16, 6, 6, 21}, {16, 16, 6, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}, {16, 16, 21, 21, 21}}, new int[][]{{0, 1, 6, 6, 21}, {1, 6, 6, 21, 21}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}}};
    static final int[] kCoeffBasePositionContextOffset = new int[]{26, 31, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36, 36};
    static final int[] kInterIntraToIntraMode = new int[]{0, 1, 2, 9};
    static final int kIntraBlockCopyDelayPixels = 256;
    static final int kIntraBlockCopyDelay64x64Blocks = 4;
    static final int[][] k4x4SizeToTransformSize = new int[][]{{0, 1, 2, 19, 19}, {3, 4, 5, 6, 19}, {7, 8, 9, 10, 11}, {19, 12, 13, 14, 15}, {19, 19, 16, 17, 18}};
    static final int[] kModeToTransformType = new int[]{0, 2, 1, 0, 3, 2, 1, 1, 2, 3, 2, 1, 3, 0};
    static final int[][] kInverseTransformTypeBySet = new int[][]{{9, 0, 10, 11, 3, 2, 1}, {9, 0, 3, 2, 1}, {9, 10, 11, 12, 13, 14, 15, 0, 2, 1, 5, 4, 3, 6, 8, 7}, {9, 10, 11, 0, 2, 1, 5, 4, 3, 6, 8, 7}, {9, 0}};
    static final int[] kAdjustedTransformSize = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 12, 13, 14, 14, 13, 14, 14};
    static final int[] kUVTransformSize = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 12, 13, 14, 14, 13, 14, 14, 14, 14, 14};
    static final int[] kTransformSizeContext = new int[]{0, 1, 1, 1, 1, 2, 2, 1, 2, 2, 3, 3, 2, 3, 3, 4, 3, 4, 4};
    static final int[] kSgrProjDefaultMultiplier = new int[]{-32, 31};
    static final int[] kWienerDefaultFilter = new int[]{3, -7, 15};
    static final int[][] kCompoundToSinglePredictionMode = new int[][]{{14, 14}, {15, 15}, {14, 17}, {17, 14}, {15, 17}, {17, 15}, {16, 16}, {17, 17}};
    static final int kMinimumMajorBitstreamLevel = 2;
    static final int kSelectScreenContentTools = 2;
    static final int kSelectIntegerMv = 2;
    static final int kLoopRestorationTileSizeMax = 256;
    static final int kGlobalMotionAlphaBits = 12;
    static final int kGlobalMotionTranslationBits = 12;
    static final int kGlobalMotionTranslationOnlyBits = 9;
    static final int kGlobalMotionAlphaPrecisionBits = 15;
    static final int kGlobalMotionTranslationPrecisionBits = 6;
    static final int kGlobalMotionTranslationOnlyPrecisionBits = 3;
    static final int kMaxTileWidth = 4096;
    static final int kMaxTileArea = 0x900000;
    static final int kPrimaryReferenceNone = 7;
    static final int kScalabilitySS = 14;
    private static final int[] kObmcMask = new int[]{45, 64, 39, 50, 59, 64, 36, 42, 48, 53, 57, 61, 64, 64, 34, 37, 40, 43, 46, 49, 52, 54, 56, 58, 60, 61, 64, 64, 64, 64, 33, 35, 36, 38, 40, 41, 43, 44, 45, 47, 48, 50, 51, 52, 53, 55, 56, 57, 58, 59, 60, 60, 61, 62, 64, 64, 64, 64, 64, 64, 64, 64};
    static final int[][] kInnerThresh = new int[][]{{1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63}, {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8}, {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7}, {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6}, {1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5}, {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4}, {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3}, {1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2}};
    static final int[][] kOuterThresh = new int[][]{{5, 7, 10, 13, 16, 19, 22, 25, 28, 31, 34, 37, 40, 43, 46, 49, 52, 55, 58, 61, 64, 67, 70, 73, 76, 79, 82, 85, 88, 91, 94, 97, 100, 103, 106, 109, 112, 115, 118, 121, 124, 127, 130, 133, 136, 139, 142, 145, 148, 151, 154, 157, 160, 163, 166, 169, 172, 175, 178, 181, 184, 187, 190, 193}, {5, 7, 9, 11, 14, 16, 19, 21, 24, 26, 29, 31, 34, 36, 39, 41, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 126, 128, 130, 132, 134, 136, 138}, {5, 7, 9, 11, 14, 16, 19, 21, 24, 26, 29, 31, 34, 36, 39, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 63, 65, 67, 69, 71, 73, 75, 77, 79, 81, 83, 85, 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109, 111, 113, 115, 117, 119, 121, 123, 125, 127, 129, 131, 133, 135, 137}, {5, 7, 9, 11, 14, 16, 19, 21, 24, 26, 29, 31, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 126, 128, 130, 132, 134, 136}, {5, 7, 9, 11, 14, 16, 19, 21, 24, 26, 29, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 63, 65, 67, 69, 71, 73, 75, 77, 79, 81, 83, 85, 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109, 111, 113, 115, 117, 119, 121, 123, 125, 127, 129, 131, 133, 135}, {5, 7, 9, 11, 13, 15, 17, 19, 22, 24, 26, 28, 31, 33, 35, 37, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 126, 128, 130, 132, 134}, {5, 7, 9, 11, 13, 15, 17, 19, 22, 24, 26, 28, 31, 33, 35, 37, 39, 41, 43, 45, 47, 49, 51, 53, 55, 57, 59, 61, 63, 65, 67, 69, 71, 73, 75, 77, 79, 81, 83, 85, 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109, 111, 113, 115, 117, 119, 121, 123, 125, 127, 129, 131, 133}, {5, 7, 9, 11, 13, 15, 17, 19, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 126, 128, 130, 132}};
    static final int kSuperBlockStateNone = 0;
    static final int kSuperBlockStateParsed = 1;
    static final int kSuperBlockStateScheduled = 2;
    static final int kSuperBlockStateDecoded = 3;
    static final int kReferenceScaleShift = 14;
    static final int kAngleStep = 3;
    static final int[] kPredictionModeToAngle = new int[]{0, 90, 180, 45, 135, 113, 157, 203, 67, 0, 0, 0, 0};
    static final BitMaskSet kNeedsLeftAndTop = new BitMaskSet(9, 11, 10, 12);
    static final int kIntraPredictorDcFill = 0;
    static final int kIntraPredictorDcTop = 1;
    static final int kIntraPredictorDcLeft = 2;
    static final int kIntraPredictorDc = 3;
    static final int kIntraPredictorVertical = 4;
    static final int kIntraPredictorHorizontal = 5;
    static final int kIntraPredictorPaeth = 6;
    static final int kIntraPredictorSmooth = 7;
    static final int kIntraPredictorSmoothVertical = 8;
    static final int kIntraPredictorSmoothHorizontal = 9;
    static final int kNumIntraPredictors = 10;
    static final int kTransform1dDct = 0;
    static final int kTransform1dAdst = 1;
    static final int kTransform1dIdentity = 2;
    static final int kTransform1dWht = 3;
    static final int kNumTransform1ds = 4;
    static final int kTransform1dSize4 = 0;
    static final int kTransform1dSize8 = 1;
    static final int kTransform1dSize16 = 2;
    static final int kTransform1dSize32 = 3;
    static final int kTransform1dSize64 = 4;
    static final int kNumTransform1dSizes = 5;
    static final int kLoopFilterSize4 = 0;
    static final int kLoopFilterSize6 = 1;
    static final int kLoopFilterSize8 = 2;
    static final int kLoopFilterSize14 = 3;
    static final int kNumLoopFilterSizes = 4;
    static final int[] kRowTransform = new int[]{0, 1, 0, 1, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 1};
    static final int[] kColumnTransform = new int[]{0, 0, 1, 1, 0, 1, 1, 1, 1, 2, 0, 2, 1, 2, 1, 2};
    static final int kRow = 0;
    static final int kColumn = 1;
    static final int kStep64x64 = 16;
    static int kCdefSkip = 8;
    static final int[][][] kCdefUvDirection = new int[][][]{new int[][]{{0, 1, 2, 3, 4, 5, 6, 7}, {1, 2, 2, 2, 3, 4, 6, 0}}, new int[][]{{7, 0, 2, 4, 5, 6, 6, 6}, {0, 1, 2, 3, 4, 5, 6, 7}}};
    static final int[][] kCdefBorderRows = new int[][]{{0, 1, 62, 63}, {0, 1, 30, 31}};
    static final int kWarpModelTranslationClamp = 0x800000;
    static final int kWarpModelAffineClamp = 8192;
    static final int kLargestMotionVectorDiff = 256;
    static final int[] kDivisorLookup = new int[]{16384, 16320, 16257, 16194, 16132, 16070, 16009, 15948, 15888, 15828, 15768, 15709, 15650, 15592, 15534, 15477, 15420, 15364, 15308, 15252, 15197, 15142, 15087, 15033, 14980, 14926, 14873, 14821, 14769, 14717, 14665, 14614, 14564, 14513, 14463, 14413, 14364, 14315, 14266, 14218, 14170, 14122, 14075, 14028, 13981, 13935, 13888, 13843, 13797, 13752, 13707, 13662, 13618, 13574, 13530, 13487, 13443, 13400, 13358, 13315, 13273, 13231, 13190, 13148, 13107, 13066, 13026, 12985, 12945, 12906, 12866, 12827, 12788, 12749, 12710, 12672, 12633, 12596, 12558, 12520, 12483, 12446, 12409, 12373, 12336, 12300, 12264, 12228, 12193, 12157, 12122, 12087, 12053, 12018, 11984, 11950, 11916, 11882, 11848, 11815, 11782, 11749, 11716, 11683, 11651, 11619, 11586, 11555, 11523, 11491, 11460, 11429, 11398, 11367, 11336, 11305, 11275, 11245, 11215, 11185, 11155, 11125, 11096, 11067, 11038, 11009, 10980, 10951, 10923, 10894, 10866, 10838, 10810, 10782, 10755, 10727, 10700, 10673, 10645, 10618, 10592, 10565, 10538, 10512, 10486, 10460, 10434, 10408, 10382, 10356, 10331, 10305, 10280, 10255, 10230, 10205, 10180, 10156, 10131, 10107, 10082, 10058, 10034, 10010, 9986, 9963, 9939, 9916, 9892, 9869, 9846, 9823, 9800, 9777, 9754, 9732, 9709, 9687, 9664, 9642, 9620, 9598, 9576, 9554, 9533, 9511, 9489, 9468, 9447, 9425, 9404, 9383, 9362, 9341, 9321, 9300, 9279, 9259, 9239, 9218, 9198, 9178, 9158, 9138, 9118, 9098, 9079, 9059, 9039, 9020, 9001, 8981, 8962, 8943, 8924, 8905, 8886, 8867, 8849, 8830, 8812, 8793, 8775, 8756, 8738, 8720, 8702, 8684, 8666, 8648, 8630, 8613, 8595, 8577, 8560, 8542, 8525, 8508, 8490, 8473, 8456, 8439, 8422, 8405, 8389, 8372, 8355, 8339, 8322, 8306, 8289, 8273, 8257, 8240, 8224, 8208, 8192};
    static final int kChromaSamplePositionUnknown = 0;
    static final int kChromaSamplePositionVertical = 1;
    static final int kChromaSamplePositionColocated = 2;
    static final int kChromaSamplePositionReserved = 3;
    static final int kImageFormatYuv420 = 0;
    static final int kImageFormatYuv422 = 1;
    static final int kImageFormatYuv444 = 2;
    static final int kImageFormatMonochrome400 = 3;
    static final int kColorPrimaryBt709 = 1;
    static final int kColorPrimaryUnspecified = 2;
    static final int kColorPrimaryBt470M = 4;
    static final int kColorPrimaryBt470Bg = 5;
    static final int kColorPrimaryBt601 = 6;
    static final int kColorPrimarySmpte240 = 7;
    static final int kColorPrimaryGenericFilm = 8;
    static final int kColorPrimaryBt2020 = 9;
    static final int kColorPrimaryXyz = 10;
    static final int kColorPrimarySmpte431 = 11;
    static final int kColorPrimarySmpte432 = 12;
    static final int kColorPrimaryEbu3213 = 22;
    static final int kMaxColorPrimaries = 255;
    static final int kTransferCharacteristicsBt709 = 1;
    static final int kTransferCharacteristicsUnspecified = 2;
    static final int kTransferCharacteristicsBt470M = 4;
    static final int kTransferCharacteristicsBt470Bg = 5;
    static final int kTransferCharacteristicsBt601 = 6;
    static final int kTransferCharacteristicsSmpte240 = 7;
    static final int kTransferCharacteristicsLinear = 8;
    static final int kTransferCharacteristicsLog100 = 9;
    static final int kTransferCharacteristicsLog100Sqrt10 = 10;
    static final int kTransferCharacteristicsIec61966 = 11;
    static final int kTransferCharacteristicsBt1361 = 12;
    static final int kTransferCharacteristicsSrgb = 13;
    static final int kTransferCharacteristicsBt2020TenBit = 14;
    static final int kTransferCharacteristicsBt2020TwelveBit = 15;
    static final int kTransferCharacteristicsSmpte2084 = 16;
    static final int kTransferCharacteristicsSmpte428 = 17;
    static final int kTransferCharacteristicsHlg = 18;
    static final int kMaxTransferCharacteristics = 255;
    static final int kMatrixCoefficientsIdentity = 0;
    static final int kMatrixCoefficientsBt709 = 1;
    static final int kMatrixCoefficientsUnspecified = 2;
    static final int kMatrixCoefficientsFcc = 4;
    static final int kMatrixCoefficientsBt470BG = 5;
    static final int kMatrixCoefficientsBt601 = 6;
    static final int kMatrixCoefficientsSmpte240 = 7;
    static final int kMatrixCoefficientsSmpteYcgco = 8;
    static final int kMatrixCoefficientsBt2020Ncl = 9;
    static final int kMatrixCoefficientsBt2020Cl = 10;
    static final int kMatrixCoefficientsSmpte2085 = 11;
    static final int kMatrixCoefficientsChromatNcl = 12;
    static final int kMatrixCoefficientsChromatCl = 13;
    static final int kMatrixCoefficientsIctcp = 14;
    static final int kMaxMatrixCoefficients = 255;
    static final int kColorRangeStudio = 0;
    static final int kColorRangeFull = 1;
    static final int kLibgav1ImageFormatYuv420 = 0;
    static final int kLibgav1ImageFormatYuv422 = 1;
    static final int kLibgav1ImageFormatYuv444 = 2;
    static final int kLibgav1ImageFormatMonochrome400 = 3;
    static final int[][][] kMaxQueueSize = new int[][][]{new int[][]{{768, 512}, {512, 384}}, new int[][]{{3072, 2048}, {2048, 1536}}};
    static final int kProfile0 = 0;
    static final int kProfile1 = 1;
    static final int kProfile2 = 2;
    static final int kMaxProfiles = 3;
    static final int kGlobalMotionTransformationTypeIdentity = 0;
    static final int kGlobalMotionTransformationTypeTranslation = 1;
    static final int kGlobalMotionTransformationTypeRotZoom = 2;
    static final int kGlobalMotionTransformationTypeAffine = 3;
    static final int kNumGlobalMotionTransformationTypes = 4;
    static final int kFrameStateUnknown = 0;
    static final int kFrameStateStarted = 1;
    static final int kFrameStateParsed = 2;
    static final int kFrameStateDecoded = 3;
    static int kIntraEdgeBufferSize = 144;
    static int kIntraEdgeFilterTestMaxSize = 129;
    static int[] kIntraEdgeFilterTestFixedInput = new int[]{159, 208, 54, 136, 205, 124, 125, 165, 164, 63, 171, 143, 210, 236, 253, 233, 139, 113, 66, 211, 133, 61, 91, 123, 187, 76, 110, 172, 61, 103, 239, 147, 247, 120, 18, 106, 180, 159, 208, 54, 136, 205, 124, 125, 165, 164, 63, 171, 143, 210, 236, 253, 233, 139, 113, 66, 211, 133, 61, 91, 123, 187, 76, 110, 172, 61, 103, 239, 147, 247, 120, 18, 106, 180, 159, 208, 54, 136, 205, 124, 125, 165, 164, 63, 171, 143, 210, 236, 253, 233, 139, 113, 66, 211, 133, 61, 91, 123, 187, 76, 110, 172, 61, 103, 239, 147, 247, 120, 18, 106, 180, 159, 208, 54, 136, 205, 124, 125, 165, 164, 63, 171, 143, 210, 236, 253, 233, 139, 113};
    static int[] kIntraEdgeUpsamplerTestFixedInput = new int[]{208, 54, 136, 205, 124, 125, 165, 164, 63, 171, 143, 210, 236, 208, 54, 136, 205};
    static int[][] kIntraEdgeFilterParamList = new int[][]{{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}, {5, 1}, {5, 2}, {5, 3}, {9, 1}, {9, 2}, {9, 3}, {17, 1}, {17, 2}, {17, 3}, {33, 1}, {33, 2}, {33, 3}, {50, 1}, {50, 2}, {50, 3}, {55, 1}, {55, 2}, {55, 3}, {65, 1}, {65, 2}, {65, 3}, {129, 1}, {129, 2}, {129, 3}};

    D() {
    }

    static int Square(int x) {
        return x * x;
    }

    static int bitScanReverse(long bb) {
        return 63 - Long.numberOfLeadingZeros(bb);
    }

    static boolean IsIntraFrame(int type) {
        return type == 0 || type == 2;
    }

    static void ExtendLine(int[] line_start, int line_startPos, int width, int left, int right) {
        int[] start;
        int[] src = start = line_start;
        int srcPos = line_startPos;
        int[] dst = start;
        int dstPos = srcPos - left;
        if (srcPos >= 0 && dstPos >= 0) {
            Mem.set(dst, dstPos, src[srcPos], left);
            Mem.set(dst, dstPos + left + width, src[srcPos + width - 1], right);
        }
    }

    static void MemSetBlockBoolean(int rows, int columns, boolean value, boolean[] dst, int dstPos, int stride) {
        do {
            Mem.set(dst, dstPos, value, columns);
            dstPos += stride;
        } while (--rows != 0);
    }

    static void MemSetBlock(int rows, int columns, int value, int[] dst, int dstPos, int stride) {
        do {
            Mem.set(dst, dstPos, value, columns);
            dstPos += stride;
        } while (--rows != 0);
    }

    static void SuperRes(int[] src, int srcPos, int source_stride, int height, int downscaled_width, int upscaled_width, int initial_subpixel_x, int step, int[] dst, int dstPos, int dest_stride) {
        int bitdepth = 8;
        srcPos -= 4;
        int y = height;
        do {
            D.ExtendLine(src, srcPos + 4, downscaled_width, 4, 4);
            int subpixel_x = initial_subpixel_x;
            int x = 0;
            do {
                int sum = 0;
                int[] src_x = src;
                int src_xPos = srcPos + subpixel_x >> 14;
                int src_x_subpixel = (subpixel_x & 0x3FFF) >> 8;
                sum -= src_x[src_xPos + 0] * kUpscaleFilterUnsigned[src_x_subpixel][0];
                sum += src_x[src_xPos + 1] * kUpscaleFilterUnsigned[src_x_subpixel][1];
                sum -= src_x[src_xPos + 2] * kUpscaleFilterUnsigned[src_x_subpixel][2];
                sum += src_x[src_xPos + 3] * kUpscaleFilterUnsigned[src_x_subpixel][3];
                sum += src_x[src_xPos + 4] * kUpscaleFilterUnsigned[src_x_subpixel][4];
                sum -= src_x[src_xPos + 5] * kUpscaleFilterUnsigned[src_x_subpixel][5];
                sum += src_x[src_xPos + 6] * kUpscaleFilterUnsigned[src_x_subpixel][6];
                dst[dstPos + x] = D.Clip3(D.RightShiftWithRounding(sum -= src_x[src_xPos + 7] * kUpscaleFilterUnsigned[src_x_subpixel][7], 7), 0, (1 << bitdepth) - 1);
                subpixel_x += step;
            } while (++x < upscaled_width);
            srcPos += source_stride;
            dstPos += dest_stride;
        } while (--y != 0);
    }

    static int GetRelativeDistance(int a, int b, int order_hint_shift_bits) {
        int diff = a - b;
        return diff << order_hint_shift_bits >> order_hint_shift_bits;
    }

    static int clz(int mask) {
        int vv = D.bitScanReverse(mask);
        return 31 - vv;
    }

    static long clz64(long mask) {
        long vv = D.bitScanReverse(mask);
        return 63L - vv;
    }

    static int FloorLog2Int(int x) {
        int s = 0;
        while (x != 0) {
            x >>= 1;
            ++s;
        }
        return s - 1;
    }

    static int FloorLog2(int x) {
        int s = 0;
        while (x != 0) {
            x >>>= 1;
            ++s;
        }
        return s - 1;
    }

    static long FloorLog2(long x) {
        int s = 0;
        while (x != 0L) {
            x >>>= 1;
            ++s;
        }
        return s - 1;
    }

    static int FloorLog2old(int x) {
        int s = 0;
        while (x != 0) {
            x >>= 1;
            ++s;
        }
        return s - 1;
    }

    static int CeilLog2(int n) {
        return n < 2 ? 0 : D.FloorLog2(n - 1) + 1;
    }

    static int RightShiftWithCeiling(int value, int bits) {
        return value + (1 << bits) - 1 >> bits;
    }

    static int RightShiftWithRounding(int value, int bits) {
        return value + (1 << bits >> 1) >> bits;
    }

    static int RightShiftWithRoundingUInt32(int value, int bits) {
        long value64 = (long)value & 0xFFFFFFFFL;
        if (value64 > 0xFFFFFFFL) {
            return D.RightShiftWithRounding(value64, bits);
        }
        return value + (1 << bits >> 1) >> bits;
    }

    static int RightShiftWithRounding(long value, int bits) {
        return (int)(value + (1L << bits >> 1) >> bits);
    }

    static int RightShiftWithRoundingSigned(int value, int bits) {
        return D.RightShiftWithRounding(value + (value >> 31), bits);
    }

    static int RightShiftWithRoundingSigned(long value, int bits) {
        return D.RightShiftWithRounding(value + (value >> 63), bits);
    }

    static int Clip3(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    static int Constrain(int diff, int threshold, int damping) {
        damping = Math.max(0, damping - D.FloorLog2(threshold));
        int sign = diff < 0 ? -1 : 1;
        return sign * D.Clip3(threshold - (Math.abs(diff) >> damping), 0, Math.abs(diff));
    }

    static int Mod32(int n) {
        return n & 0x1F;
    }

    static int Mod64(int n) {
        return n & 0x3F;
    }

    static int GetFilterIndex(int filter_index, int length) {
        if (length <= 4) {
            if (filter_index == 0 || filter_index == 2) {
                return 4;
            }
            if (filter_index == 1) {
                return 5;
            }
        }
        return filter_index;
    }

    static int BitdepthToArrayIndex(int bitdepth) {
        return bitdepth - 8 >> 1;
    }

    static void SetBlock(int rows, int columns, BlockParams value, int dst, int stride, BlockParams[] cache) {
        do {
            for (int i = dst; i < dst + columns; ++i) {
                cache[i] = value;
            }
            dst += stride;
        } while (--rows != 0);
    }

    int GetSquareTransformSize(int pixels) {
        switch (pixels) {
            case 64: 
            case 128: {
                return 18;
            }
            case 32: {
                return 14;
            }
            case 16: {
                return 9;
            }
            case 8: {
                return 4;
            }
        }
        return 0;
    }

    static int SubsampledValue(int value, int subsampling) {
        return value + subsampling >> subsampling;
    }

    static void OverlapBlendVertical(int[] pred, int predPos, int pred_stride, int width, int height, int[] obmc_pred, int obmc_predPos, int obmc_pred_stride) {
        int[] mask = kObmcMask;
        int maskPos = height - 2;
        for (int y = 0; y < height; ++y) {
            int mask_value = mask[maskPos + y];
            for (int x = 0; x < width; ++x) {
                pred[predPos + x] = D.RightShiftWithRounding(mask_value * pred[predPos + x] + (64 - mask_value) * obmc_pred[obmc_predPos + x], 6);
            }
            predPos += pred_stride;
            obmc_predPos += obmc_pred_stride;
        }
    }

    static void OverlapBlendHorizontal(int[] pred, int predPos, int pred_stride, int width, int height, int[] obmc_pred, int obmc_predPos, int obmc_pred_stride) {
        int[] mask = kObmcMask;
        int maskPos = width - 2;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int mask_value = mask[maskPos + x];
                pred[predPos + x] = D.RightShiftWithRounding(mask_value * pred[predPos + x] + (64 - mask_value) * obmc_pred[obmc_predPos + x], 6);
            }
            predPos += pred_stride;
            obmc_predPos += obmc_pred_stride;
        }
    }

    static int GetResidualBufferSize(int rows, int columns, int subsampling_x, int subsampling_y, int residual_size) {
        int subsampling_multiplier_num = 2 + (4 >> subsampling_x >> subsampling_y);
        int number_elements = rows * columns * subsampling_multiplier_num >> 1;
        int tx_padding = 128;
        return residual_size * (number_elements + 128);
    }

    static int RowOrColumn4x4ToPixel(int row_or_column4x4, int plane, int subsampling) {
        return row_or_column4x4 * 4 >> (plane == 0 ? 0 : subsampling);
    }

    static int GetDeblockPosition(int luma_position, int subsampling) {
        return luma_position | subsampling;
    }

    static int ComposeImageFormat(boolean is_monochrome, int subsampling_x, int subsampling_y) {
        int image_format = subsampling_x == 0 ? 2 : (subsampling_y == 0 ? 1 : (!is_monochrome ? 0 : 3));
        return image_format;
    }

    static void DecomposeImageFormat(int image_format, boolean[] is_monochrome, int[] subsampling_x, int[] subsampling_y) {
        is_monochrome[0] = false;
        subsampling_x[0] = 1;
        subsampling_y[0] = 1;
        switch (image_format) {
            case 0: {
                break;
            }
            case 1: {
                subsampling_y[0] = 0;
                break;
            }
            case 2: {
                subsampling_y[0] = 0;
                subsampling_x[0] = 0;
                break;
            }
            default: {
                is_monochrome[0] = true;
            }
        }
    }

    static int TransformSizeToSquareTransformIndex(int tx_size) {
        return tx_size / 4;
    }

    static boolean IsBlockSmallerThan8x8(int size) {
        return size < 4 && size != 2;
    }

    static boolean IsBlockDimension4(int size) {
        return size < 4 || size == 7;
    }

    static int GetPlaneType(int plane) {
        return plane != 0 ? 1 : 0;
    }

    static boolean IsDirectionalMode(int mode) {
        return mode >= 1 && mode <= 8;
    }

    static int[][] fromView2D(Array2DView view) {
        int[][] result = new int[view.rows_][view.columns_];
        int p = 0;
        for (int h = 0; h < view.rows_; ++h) {
            for (int w = 0; w < view.columns_; ++w) {
                result[h][w] = view.data_[p++];
            }
        }
        return result;
    }

    static Array2DView toView2D(int[][] arr) {
        int[] data_ = new int[arr.length * arr[0].length];
        int rows_ = arr.length;
        int columns_ = arr[0].length;
        Array2DView view = new Array2DView(rows_, columns_, data_);
        int p = 0;
        for (int h = 0; h < view.rows_; ++h) {
            for (int w = 0; w < view.columns_; ++w) {
                view.data_[p++] = arr[h][w];
            }
        }
        return view;
    }

    static int Align(int value, int alignment) {
        int alignment_mask = alignment - 1;
        return value + alignment_mask & ~alignment_mask;
    }

    static class Array2DView {
        private int rows_ = 0;
        private int columns_ = 0;
        int[] data_ = null;

        Array2DView() {
        }

        Array2DView(int rows, int columns, int[] data) {
            this.rows_ = rows;
            this.columns_ = columns;
            this.data_ = data;
        }

        void Reset(int rows, int columns) {
            this.rows_ = rows;
            this.columns_ = columns;
            this.data_ = new int[rows * columns];
        }

        void Reset(int rows, int columns, int[] data) {
            this.rows_ = rows;
            this.columns_ = columns;
            this.data_ = data;
        }

        int get(int ry, int rx) {
            return this.data_[ry * this.columns_ + rx];
        }

        void set(int ry, int rx, int value) {
            this.data_[ry * this.columns_ + rx] = value;
        }

        int rows() {
            return this.rows_;
        }

        int columns() {
            return this.columns_;
        }
    }

    static class BitMaskSet {
        final int mask_;

        BitMaskSet(int mask) {
            this.mask_ = mask;
        }

        BitMaskSet(int v1, int v2) {
            this.mask_ = 1 << v1 | 1 << v2;
        }

        BitMaskSet(int v1, int v2, int v3) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3;
        }

        BitMaskSet(int v1, int v2, int v3, int v4) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4;
        }

        BitMaskSet(int v1, int v2, int v3, int v4, int v5) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4 | 1 << v5;
        }

        BitMaskSet(int v1, int v2, int v3, int v4, int v5, int v6) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4 | 1 << v5 | 1 << v6;
        }

        BitMaskSet(int v1, int v2, int v3, int v4, int v5, int v6, int v7) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4 | 1 << v5 | 1 << v6 | 1 << v7;
        }

        BitMaskSet(int v1, int v2, int v3, int v4, int v5, int v6, int v7, int v8, int v9) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4 | 1 << v5 | 1 << v6 | 1 << v7 | 1 << v8 | 1 << v9;
        }

        BitMaskSet(int v1, int v2, int v3, int v4, int v5, int v6, int v7, int v8, int v9, int v10) {
            this.mask_ = 1 << v1 | 1 << v2 | 1 << v3 | 1 << v4 | 1 << v5 | 1 << v6 | 1 << v7 | 1 << v8 | 1 << v9 | 1 << v10;
        }

        boolean Contains(int value) {
            return BitMaskSet.MaskContainsValue(this.mask_, value);
        }

        static boolean MaskContainsValue(int mask, int value) {
            return (mask >> value & 1) != 0;
        }
    }

    static class EdgeFilterParams {
        int size;
        int strength;

        EdgeFilterParams() {
        }
    }

    static class ThreadingStrategy {
        int tile_thread_count_ = 0;
        int max_tile_index_for_row_threads_ = 0;
        boolean frame_parallel_ = false;
        ThreadPool thread_pool_ = null;

        ThreadingStrategy() {
        }

        boolean Reset(ObuFrameHeader frameHeader, int threads) {
            return true;
        }

        ThreadPool post_filter_thread_pool() {
            return this.frame_parallel_ ? null : this.thread_pool_;
        }

        ThreadPool row_thread_pool(int tile_index) {
            return tile_index < this.max_tile_index_for_row_threads_ ? this.thread_pool_ : null;
        }

        ThreadPool thread_pool() {
            return this.thread_pool_;
        }
    }

    static class DecoderSettings {
        int threads = 1;
        int post_filter_mask = 31;

        DecoderSettings() {
        }
    }

    static class FrameScratchBufferPool {
        Stack<FrameScratchBuffer> buffers_ = new Stack();
        FrameScratchBuffer fsb = new FrameScratchBuffer();

        FrameScratchBufferPool() {
        }

        FrameScratchBuffer get() {
            return this.fsb;
        }
    }

    static class EncodedFrame {
        ObuSequenceHeader sequence_header;
        ObuFrameHeader frame_header;
        ArrayList<TileBuffer> tile_buffers = new ArrayList();
        DecoderState state;
        TemporalUnit temporal_unit;
        RefCountedBuffer frame;
        int position_in_temporal_unit;

        EncodedFrame(Obu obu, DecoderState state, RefCountedBuffer frame, int position_in_temporal_unit) {
            this.sequence_header = obu.sequence_header();
            this.frame_header = obu.frame_header();
            this.state = state;
            this.temporal_unit = null;
            this.frame = frame;
            this.position_in_temporal_unit = position_in_temporal_unit;
            this.tile_buffers = obu.tile_buffers_;
            frame.MarkFrameAsStarted();
        }
    }

    static class TemporalUnit {
        TemporalUnit() {
        }
    }

    static class ThreadPool {
        int num_threads_ = 0;

        ThreadPool() {
        }
    }

    static class TransformTreeNode {
        int x;
        int y;
        int tx_size;
        int depth = -1;

        TransformTreeNode(int x, int y, int tx_size, int depth) {
            this.x = x;
            this.y = y;
            this.tx_size = tx_size;
            this.depth = depth;
        }

        TransformTreeNode(int x, int y, int tx_size) {
            this.x = x;
            this.y = y;
            this.tx_size = tx_size;
            this.depth = -1;
        }
    }

    static class BlockingCounterImpl {
        final boolean has_failure_status;
        int count_;

        BlockingCounterImpl(boolean hasFail) {
            this.has_failure_status = hasFail;
        }

        void IncrementBy(int count) {
            this.count_ += count;
        }

        void Decrement() {
            --this.count_;
        }

        void Decrement(boolean job_succeeded) {
            --this.count_;
        }
    }

    static class LoopRestorationUnitInfo {
        int row_start;
        int row_end;
        int column_start;
        int column_end;

        LoopRestorationUnitInfo() {
        }
    }

    static class LoopRestorationInfo {
        static int kSgrProjReadControl = 4;
        RestorationUnitInfo[][] loop_restoration_info_ = new RestorationUnitInfo[3][];
        DynamicBufferRestorationUnitInfo loop_restoration_info_buffer_ = new DynamicBufferRestorationUnitInfo();
        boolean[] plane_needs_filtering_ = new boolean[3];
        LoopRestoration loop_restoration_ = new LoopRestoration();
        int subsampling_x_;
        int subsampling_y_;
        int[] num_horizontal_units_ = new int[3];
        int[] num_vertical_units_ = new int[3];
        int[] num_units_ = new int[3];
        static final int[] kBitstreamRestorationTypeMap = new int[]{0, 2, 3};

        LoopRestorationInfo() {
        }

        RestorationUnitInfo loop_restoration_info(int plane, int unit_id) {
            return this.loop_restoration_info_[plane][unit_id];
        }

        int num_horizontal_units(int plane) {
            return this.num_horizontal_units_[plane];
        }

        int num_vertical_units(int plane) {
            return this.num_vertical_units_[plane];
        }

        int num_units(int plane) {
            return this.num_units_[plane];
        }

        static int CountLeadingZeroCoefficients(int[] filter) {
            int number_zero_coefficients = 0;
            if (filter[0] == 0) {
                ++number_zero_coefficients;
                if (filter[1] == 0) {
                    ++number_zero_coefficients;
                    if (filter[2] == 0) {
                        ++number_zero_coefficients;
                    }
                }
            }
            return number_zero_coefficients;
        }

        boolean PopulateUnitInfoForSuperBlock(int plane, int block_size, boolean is_superres_scaled, int superres_scale_denominator, int row4x4, int column4x4, LoopRestorationUnitInfo unit_info) {
            if (!this.plane_needs_filtering_[plane]) {
                return false;
            }
            int numerator_column = is_superres_scaled ? superres_scale_denominator : 1;
            int pixel_column_start = D.RowOrColumn4x4ToPixel(column4x4, plane, this.subsampling_x_);
            int pixel_column_end = D.RowOrColumn4x4ToPixel(column4x4 + kNum4x4BlocksWide[block_size], plane, this.subsampling_x_);
            int unit_row_log2 = this.loop_restoration_.unit_size_log2[plane];
            int denominator_column_log2 = unit_row_log2 + (is_superres_scaled ? 3 : 0);
            int pixel_row_start = D.RowOrColumn4x4ToPixel(row4x4, plane, this.subsampling_y_);
            int pixel_row_end = D.RowOrColumn4x4ToPixel(row4x4 + kNum4x4BlocksHigh[block_size], plane, this.subsampling_y_);
            unit_info.column_start = D.RightShiftWithCeiling(pixel_column_start * numerator_column, denominator_column_log2);
            unit_info.column_end = D.RightShiftWithCeiling(pixel_column_end * numerator_column, denominator_column_log2);
            unit_info.row_start = D.RightShiftWithCeiling(pixel_row_start, unit_row_log2);
            unit_info.row_end = D.RightShiftWithCeiling(pixel_row_end, unit_row_log2);
            unit_info.column_end = Math.min(unit_info.column_end, this.num_horizontal_units_[plane]);
            unit_info.row_end = Math.min(unit_info.row_end, this.num_vertical_units_[plane]);
            return true;
        }

        void ReadUnitCoefficients(Entropy reader, Symbol symbol_decoder_context, int plane, int unit_id, RestorationUnitInfo[] reference_unit_info) {
            boolean use_sgrproj;
            int unit_restoration_type = 0;
            if (this.loop_restoration_.type[plane] == 1) {
                unit_restoration_type = kBitstreamRestorationTypeMap[reader.readSymbol(symbol_decoder_context.restoration_type_cdf, 3)];
            } else if (this.loop_restoration_.type[plane] == 2) {
                boolean use_wiener = reader.readSymbol(symbol_decoder_context.use_wiener_cdf);
                if (use_wiener) {
                    unit_restoration_type = 2;
                }
            } else if (this.loop_restoration_.type[plane] == 3 && (use_sgrproj = reader.readSymbol(symbol_decoder_context.use_sgrproj_cdf))) {
                unit_restoration_type = 3;
            }
            this.loop_restoration_info_[plane][unit_id].type = unit_restoration_type;
            if (unit_restoration_type == 2) {
                this.ReadWienerInfo(reader, plane, unit_id, reference_unit_info);
            } else if (unit_restoration_type == 3) {
                this.ReadSgrProjInfo(reader, plane, unit_id, reference_unit_info);
            }
        }

        private void ReadWienerInfo(Entropy reader, int plane, int unit_id, RestorationUnitInfo[] reference_unit_info) {
            for (int i = 0; i <= 1; ++i) {
                int j;
                if (plane != 0) {
                    this.loop_restoration_info_[plane][unit_id].wiener_info.filter[i][0] = 0;
                }
                int sum = 0;
                int n = j = plane != 0 ? 1 : 0;
                while (j < 3) {
                    int value;
                    int wiener_min = kWienerTapsMin[j];
                    int wiener_max = kWienerTapsMax[j];
                    int control = j + 1;
                    this.loop_restoration_info_[plane][unit_id].wiener_info.filter[i][j] = value = reader.decodeSignedSubexpWithReference(wiener_min, wiener_max + 1, reference_unit_info[plane].wiener_info.filter[i][j], control);
                    reference_unit_info[plane].wiener_info.filter[i][j] = value;
                    sum += value;
                    ++j;
                }
                this.loop_restoration_info_[plane][unit_id].wiener_info.filter[i][3] = 128 - 2 * sum;
                this.loop_restoration_info_[plane][unit_id].wiener_info.number_leading_zero_coefficients[i] = LoopRestorationInfo.CountLeadingZeroCoefficients(this.loop_restoration_info_[plane][unit_id].wiener_info.filter[i]);
            }
        }

        private void ReadSgrProjInfo(Entropy reader, int plane, int unit_id, RestorationUnitInfo[] reference_unit_info) {
            int sgr_proj_index;
            this.loop_restoration_info_[plane][unit_id].sgr_proj_info.index = sgr_proj_index = (int)reader.readLiteral(4);
            for (int i = 0; i < 2; ++i) {
                int radius = kSgrProjParams[sgr_proj_index][i * 2];
                int multiplier_min = kSgrProjMultiplierMin[i];
                int multiplier_max = kSgrProjMultiplierMax[i];
                int multiplier = 0;
                if (radius != 0) {
                    multiplier = reader.decodeSignedSubexpWithReference(multiplier_min, multiplier_max + 1, reference_unit_info[plane].sgr_proj_info.multiplier[i], kSgrProjReadControl);
                } else {
                    int[] kMultiplier = new int[]{0, 95};
                    multiplier = kMultiplier[i];
                }
                this.loop_restoration_info_[plane][unit_id].sgr_proj_info.multiplier[i] = multiplier;
                reference_unit_info[plane].sgr_proj_info.multiplier[i] = multiplier;
            }
        }

        static int SubsampledValue(int value, int subsampling) {
            return value + subsampling >> subsampling;
        }

        boolean reset(LoopRestoration loop_restoration, int width, int height, int subsampling_x, int subsampling_y, boolean is_monochrome) {
            this.loop_restoration_ = loop_restoration;
            this.subsampling_x_ = subsampling_x;
            this.subsampling_y_ = subsampling_y;
            int num_planes = is_monochrome ? 1 : 3;
            int total_num_units = 0;
            for (int plane = 0; plane < num_planes; ++plane) {
                if (this.loop_restoration_.type[plane] == 0) {
                    this.plane_needs_filtering_[plane] = false;
                    continue;
                }
                this.plane_needs_filtering_[plane] = true;
                int plane_width = plane == 0 ? width : LoopRestorationInfo.SubsampledValue(width, this.subsampling_x_);
                int plane_height = plane == 0 ? height : LoopRestorationInfo.SubsampledValue(height, this.subsampling_y_);
                this.num_horizontal_units_[plane] = Math.max(1, D.RightShiftWithRounding(plane_width, this.loop_restoration_.unit_size_log2[plane]));
                this.num_vertical_units_[plane] = Math.max(1, D.RightShiftWithRounding(plane_height, this.loop_restoration_.unit_size_log2[plane]));
                this.num_units_[plane] = this.num_horizontal_units_[plane] * this.num_vertical_units_[plane];
                total_num_units += this.num_units_[plane];
            }
            if (!this.loop_restoration_info_buffer_.Resize(total_num_units)) {
                return false;
            }
            RestorationUnitInfo[] loop_restoration_info = this.loop_restoration_info_buffer_.get();
            int lPos = 0;
            for (int plane = 0; plane < num_planes; ++plane) {
                if (this.loop_restoration_.type[plane] == 0) continue;
                this.loop_restoration_info_[plane] = loop_restoration_info;
                lPos += this.num_units_[plane];
            }
            return true;
        }
    }

    static class ResidualBufferPool {
        ResidualBufferStack buffers_ = new ResidualBufferStack();
        int buffer_size_;
        int queue_size_;

        ResidualBufferPool(boolean use_128x128_superblock, int subsampling_x, int subsampling_y, int residual_size) {
            this.buffer_size_ = D.GetResidualBufferSize(use_128x128_superblock ? 128 : 64, use_128x128_superblock ? 128 : 64, subsampling_x, subsampling_y, residual_size);
            this.queue_size_ = kMaxQueueSize[use_128x128_superblock ? 1 : 0][subsampling_x][subsampling_y];
        }

        void Reset(boolean use_128x128_superblock, int subsampling_x, int subsampling_y, int residual_size) {
            int buffer_size = D.GetResidualBufferSize(use_128x128_superblock ? 128 : 64, use_128x128_superblock ? 128 : 64, subsampling_x, subsampling_y, residual_size);
            int queue_size = kMaxQueueSize[use_128x128_superblock ? 1 : 0][subsampling_x][subsampling_y];
            this.buffer_size_ = buffer_size;
            this.queue_size_ = queue_size;
        }

        ResidualBuffer get() {
            ResidualBuffer buffer = this.buffers_.pop();
            if (buffer == null) {
                buffer = ResidualBuffer.create(this.buffer_size_, this.queue_size_);
            }
            return buffer;
        }
    }

    static class ResidualBufferStack {
        private Stack<ResidualBuffer> availbuffers = new Stack();

        ResidualBufferStack() {
        }

        void push(ResidualBuffer rb) {
            this.availbuffers.push(rb);
        }

        ResidualBuffer pop() {
            return this.availbuffers.pop();
        }
    }

    static class BufferPool {
        ArrayList<RefCountedBuffer> buffers = new ArrayList();

        BufferPool() {
        }

        RefCountedBuffer GetFreeBuffer() {
            RefCountedBuffer buffer = new RefCountedBuffer();
            buffer.in_use_ = true;
            buffer.progress_row_ = -1;
            buffer.frame_state_ = 0;
            buffer.hdr_cll_set_ = false;
            buffer.hdr_mdcv_set_ = false;
            buffer.itut_t35_set_ = false;
            buffer.setBufferPool(this);
            this.buffers.add(buffer);
            return buffer;
        }
    }

    static class ResidualBuffer {
        int[] buffer_;
        QueueTransformParameters transform_parameters_ = new QueueTransformParameters();
        QueuePartitionTreeNode partition_tree_order_ = new QueuePartitionTreeNode();
        ResidualBuffer next_ = null;

        ResidualBuffer() {
        }

        static ResidualBuffer create(int buffer_size, int qSize) {
            ResidualBuffer rb = new ResidualBuffer();
            rb.buffer_ = new int[buffer_size];
            rb.transform_parameters_.init(qSize);
            rb.partition_tree_order_.init(qSize);
            return rb;
        }

        int[] buffer() {
            return this.buffer_;
        }

        QueuePartitionTreeNode partition_tree_order() {
            return this.partition_tree_order_;
        }
    }

    static class QueuePartitionTreeNode {
        PartitionTreeNode[] elements_;
        int size_ = 0;
        int end_ = 0;
        int capacity_ = 0;
        int begin_ = 0;

        QueuePartitionTreeNode() {
        }

        boolean init(int capacity) {
            this.elements_ = new PartitionTreeNode[capacity];
            this.capacity_ = capacity;
            return true;
        }

        void Push(PartitionTreeNode value) {
            this.elements_[this.end_++] = value;
            if (this.end_ == this.capacity_) {
                this.end_ = 0;
            }
            ++this.size_;
        }

        void Pop() {
            PartitionTreeNode element = this.elements_[this.begin_++];
            if (this.begin_ == this.capacity_) {
                this.begin_ = 0;
            }
            --this.size_;
        }

        PartitionTreeNode Front() {
            return this.elements_[this.begin_];
        }

        PartitionTreeNode Back() {
            int back = (this.end_ == 0 ? this.capacity_ : this.end_) - 1;
            return this.elements_[back];
        }

        void Clear() {
            while (!this.Empty()) {
                this.Pop();
            }
        }

        boolean Empty() {
            return this.size_ == 0;
        }
    }

    static class QueueTransformParameters {
        TransformParameters[] elements_;
        int size_ = 0;
        int end_ = 0;
        int capacity_ = 0;
        int begin_ = 0;

        QueueTransformParameters() {
        }

        boolean init(int capacity) {
            this.elements_ = new TransformParameters[capacity];
            this.capacity_ = capacity;
            return true;
        }

        void Push(TransformParameters value) {
            this.elements_[this.end_++] = value;
            if (this.end_ == this.capacity_) {
                this.end_ = 0;
            }
            ++this.size_;
        }

        void Pop() {
            TransformParameters element = this.elements_[this.begin_++];
            if (this.begin_ == this.capacity_) {
                this.begin_ = 0;
            }
            --this.size_;
        }

        TransformParameters Front() {
            return this.elements_[this.begin_];
        }

        TransformParameters Back() {
            int back = (this.end_ == 0 ? this.capacity_ : this.end_) - 1;
            return this.elements_[back];
        }

        void Clear() {
            while (!this.Empty()) {
                this.Pop();
            }
        }

        boolean Empty() {
            return this.size_ == 0;
        }
    }

    static class RefCountedBuffer {
        boolean in_use_ = false;
        int frame_type_ = 0;
        boolean showable_frame_ = false;
        int frame_state_;
        int progress_row_ = 0;
        boolean hdr_cll_set_;
        boolean hdr_mdcv_set_;
        boolean itut_t35_set_ = false;
        int chroma_sample_position_ = 0;
        int upscaled_width_ = 0;
        int frame_width_ = 0;
        int frame_height_ = 0;
        int render_width_ = 0;
        int render_height_ = 0;
        int columns4x4_ = 0;
        int rows4x4_ = 0;
        int spatial_id_ = 0;
        int temporal_id_ = 0;
        int[] loop_filter_ref_deltas_ = new int[8];
        int[] loop_filter_mode_deltas_ = new int[2];
        GlobalMotion[] global_motion_ = new GlobalMotion[8];
        FilmGrainParams film_grain_params_ = new FilmGrainParams();
        ReferenceInfo reference_info_ = new ReferenceInfo();
        SegmentationMap segmentation_map_ = new SegmentationMap();
        LoopFilter loopFilter_;
        Symbol frame_context_ = new Symbol();
        Segmentation segmentation_ = new Segmentation();
        BufferPool bufferPool_ = new BufferPool();
        Yuv yuv_buffer_ = new Yuv();
        BufferPool pool_ = new BufferPool();
        boolean buffer_private_data_valid_;

        RefCountedBuffer() {
        }

        int upscaled_width() {
            return this.upscaled_width_;
        }

        int frame_width() {
            return this.frame_width_;
        }

        int frame_height() {
            return this.frame_height_;
        }

        GlobalMotion[] GlobalMotions() {
            return this.global_motion_;
        }

        FilmGrainParams film_grain_params() {
            return this.film_grain_params_;
        }

        boolean showable_frame() {
            return this.showable_frame_;
        }

        void set_showable_frame(boolean value) {
            this.showable_frame_ = value;
        }

        int frame_type() {
            return this.frame_type_;
        }

        void set_frame_type(int frame_type) {
            this.frame_type_ = frame_type;
        }

        int render_width() {
            return this.render_width_;
        }

        int render_height() {
            return this.render_height_;
        }

        ReferenceInfo reference_info() {
            return this.reference_info_;
        }

        void setBufferPool(BufferPool bufferPool) {
            this.bufferPool_ = bufferPool;
        }

        void set_spatial_id(int spatialId) {
            this.spatial_id_ = spatialId;
        }

        void set_temporal_id(int temporalId) {
            this.temporal_id_ = temporalId;
        }

        SegmentationMap segmentation_map() {
            return this.segmentation_map_;
        }

        int columns4x4() {
            return this.columns4x4_;
        }

        int rows4x4() {
            return this.rows4x4_;
        }

        void GetSegmentationParameters(Segmentation segmentation) {
            RefCountedBuffer.CopySegmentationParameters(this.segmentation_, segmentation);
        }

        static void CopySegmentationParameters(Segmentation segFrom, Segmentation segTo) {
            for (int i = 0; i < 8; ++i) {
                for (int j = 0; j < 8; ++j) {
                    segTo.feature_enabled[i][j] = segFrom.feature_enabled[i][j];
                    segTo.feature_data[i][j] = segFrom.feature_data[i][j];
                }
            }
            segTo.segment_id_pre_skip = segFrom.segment_id_pre_skip;
            segTo.last_active_segment_id = segFrom.last_active_segment_id;
        }

        boolean SetFrameDimensions(ObuFrameHeader frame_header) {
            this.upscaled_width_ = frame_header.upscaled_width;
            this.frame_width_ = frame_header.width;
            this.frame_height_ = frame_header.height;
            this.render_width_ = frame_header.render_width;
            this.render_height_ = frame_header.render_height;
            this.rows4x4_ = frame_header.rows4x4;
            this.columns4x4_ = frame_header.columns4x4;
            if (frame_header.refresh_frame_flags != 0 && !D.IsIntraFrame(frame_header.frame_type)) {
                LogWriter.writeLog("should not come here");
            }
            return this.segmentation_map_.Allocate(this.rows4x4_, this.columns4x4_);
        }

        void SetSegmentationParameters(Segmentation segmentation) {
            this.segmentation_ = segmentation;
        }

        void SetLoopFilterDeltas(LoopFilter loopFilter) {
            this.loopFilter_ = loopFilter;
        }

        void SetGlobalMotions(GlobalMotion[] globalMotion) {
            this.global_motion_ = globalMotion;
        }

        void set_film_grain_params(FilmGrainParams filmGrainParams) {
            this.film_grain_params_ = this.film_grain_params_;
        }

        void SetFrameContext(Symbol context) {
            this.frame_context_ = context;
            this.frame_context_.ResetIntraFrameYModeCdf();
            this.frame_context_.ResetCounters();
        }

        void SetFrameState(int frame_state) {
            this.frame_state_ = frame_state;
        }

        void SetProgress(int progress_row) {
            this.progress_row_ = progress_row;
        }

        void MarkFrameAsStarted() {
            this.frame_state_ = 1;
        }

        boolean WaitUntilDecoded() {
            return true;
        }

        void set_chroma_sample_position(int chromaSamplePosition) {
            this.chroma_sample_position_ = chromaSamplePosition;
        }

        boolean realloc(int bitdepth, boolean isMonochrome, int width, int height, int subsampling_x, int subsampling_y, int left_border, int right_border, int top_border, int bottom_border) {
            this.yuv_buffer_.realloc(bitdepth, isMonochrome, width, height, subsampling_x, subsampling_y, left_border, right_border, top_border, bottom_border);
            this.buffer_private_data_valid_ = true;
            return true;
        }

        Yuv buffer() {
            return this.yuv_buffer_;
        }

        Symbol FrameContext() {
            return this.frame_context_;
        }

        public String toString() {
            return "RefCountedBuffer{\n\t in_use_=" + this.in_use_ + "\n\t frame_type_=" + this.frame_type_ + "\n\t showable_frame_=" + this.showable_frame_ + "\n\t frame_state_=" + this.frame_state_ + "\n\t progress_row_=" + this.progress_row_ + "\n\t hdr_cll_set_=" + this.hdr_cll_set_ + "\n\t hdr_mdcv_set_=" + this.hdr_mdcv_set_ + "\n\t itut_t35_set_=" + this.itut_t35_set_ + "\n\t chroma_sample_position_=" + this.chroma_sample_position_ + "\n\t upscaled_width_=" + this.upscaled_width_ + "\n\t frame_width_=" + this.frame_width_ + "\n\t frame_height_=" + this.frame_height_ + "\n\t render_width_=" + this.render_width_ + "\n\t render_height_=" + this.render_height_ + "\n\t columns4x4_=" + this.columns4x4_ + "\n\t rows4x4_=" + this.rows4x4_ + "\n\t spatial_id_=" + this.spatial_id_ + "\n\t temporal_id_=" + this.temporal_id_ + "\n\t loop_filter_ref_deltas_=" + Arrays.toString(this.loop_filter_ref_deltas_) + "\n\t loop_filter_mode_deltas_=" + Arrays.toString(this.loop_filter_mode_deltas_) + "\n\t global_motion_=" + Arrays.toString(this.global_motion_) + "\n\t film_grain_params_=" + String.valueOf(this.film_grain_params_) + "\n\t reference_info_=" + String.valueOf(this.reference_info_) + "\n\t segmentation_map_=" + String.valueOf(this.segmentation_map_) + "\n\t loopFilter_=" + String.valueOf(this.loopFilter_) + "\n\t frame_context_=" + String.valueOf(this.frame_context_) + "\n\t segmentation_=" + String.valueOf(this.segmentation_) + "\n\t bufferPool_=" + String.valueOf(this.bufferPool_) + "\n\t yuv_buffer_=" + String.valueOf(this.yuv_buffer_) + "\n\t pool_=" + String.valueOf(this.pool_) + "\n\t buffer_private_data_valid_=" + this.buffer_private_data_valid_ + "}";
        }
    }

    static class SegmentationMap {
        int rows4x4_ = 0;
        int columns4x4_ = 0;
        int[] segment_id_buffer_;
        Array2DView segment_id_ = new Array2DView();

        SegmentationMap() {
        }

        boolean Allocate(int rows4x4, int columns4x4) {
            if (rows4x4 * columns4x4 > this.rows4x4_ * this.columns4x4_ && rows4x4 != 0) {
                this.segment_id_buffer_ = new int[rows4x4 * columns4x4];
            }
            this.rows4x4_ = rows4x4;
            this.columns4x4_ = columns4x4;
            this.segment_id_.Reset(this.rows4x4_, this.columns4x4_, this.segment_id_buffer_);
            return true;
        }

        int segment_id(int row4x4, int column4x4) {
            return this.segment_id_.get(row4x4, column4x4);
        }

        void FillBlock(int row4x4, int column4x4, int block_width4x4, int block_height4x4, int segment_id) {
            for (int y = 0; y < block_height4x4; ++y) {
                Mem.set(this.segment_id_.data_, (row4x4 + y) * this.segment_id_.columns_ + column4x4, segment_id, block_width4x4);
            }
        }

        void Clear() {
            if (this.segment_id_buffer_ == null) {
                this.segment_id_buffer_ = new int[this.rows4x4_ * this.columns4x4_];
            }
            Mem.set(this.segment_id_buffer_, 0, this.rows4x4_ * this.columns4x4_);
        }

        void CopyFrom(SegmentationMap from) {
            Mem.cpy(this.segment_id_buffer_, from.segment_id_buffer_, this.rows4x4_ * this.columns4x4_);
        }
    }

    static class ReferenceInfo {
        int[] order_hint = new int[8];
        int[] relative_distance_from = new int[8];
        int[] relative_distance_to = new int[8];
        boolean[] skip_references = new boolean[8];
        int[] projection_divisions = new int[8];
        int[][] motion_field_reference_frame = new int[8][8];
        MotionVector[][] motion_field_mv = new MotionVector[8][8];

        ReferenceInfo() {
            for (int i = 0; i < 8; ++i) {
                for (int j = 0; j < 8; ++j) {
                    this.motion_field_mv[i][j] = new MotionVector();
                }
            }
        }
    }

    static class DecoderState {
        int[] reference_frame_id = new int[8];
        int[] reference_order_hint = new int[8];
        int current_frame_id = -1;
        int order_hint = 0;
        boolean[] reference_frame_sign_bias = new boolean[8];
        RefCountedBuffer[] reference_frame = new RefCountedBuffer[8];

        DecoderState() {
        }

        void ClearReferenceFrames() {
            LogWriter.writeLog("clear references frames called");
        }
    }

    static class TileScratchBufferPool {
        Stack<TileScratchBuffer> buffers_ = new Stack();
        TileScratchBuffer sb = new TileScratchBuffer();

        TileScratchBufferPool() {
        }

        TileScratchBuffer get() {
            return this.sb;
        }

        void release(TileScratchBuffer tsb) {
            this.buffers_.push(tsb);
        }
    }

    static class TileScratchBuffer {
        int pixel_size = 1;
        int unaligned_convolve_buffer_stride = 274;
        int convolve_block_buffer_stride = D.Align(this.unaligned_convolve_buffer_stride * this.pixel_size, 32);
        int convolve_buffer_height = 263;
        int convolve_block_buffer = this.convolve_buffer_height * this.convolve_block_buffer_stride;
        int[] weight_mask = new int[16384];
        int[][] prediction_buffer = new int[2][16384];
        int[][] compound_prediction_buffer_8bpp = new int[2][16384];
        int[][] cfl_luma_buffer = new int[32][32];
        boolean cfl_luma_buffer_valid;
        boolean[][] block_decoded = new boolean[3][1156];

        TileScratchBuffer() {
        }
    }

    static class ThreadingParameters {
        int[][] sb_state;
        int pending_jobs = 0;

        ThreadingParameters() {
        }
    }

    static class TransformParameters {
        int type;
        int non_zero_coeff_count;

        TransformParameters(int type, int non_zero_coeff_count) {
            this.type = type;
            this.non_zero_coeff_count = non_zero_coeff_count;
        }
    }

    static class PartitionTreeNode {
        int row4x4 = -1;
        int column4x4 = -1;
        int block_size = 23;

        PartitionTreeNode(int row4x4, int column4x4, int block_size) {
            this.row4x4 = row4x4;
            this.column4x4 = column4x4;
            this.block_size = block_size;
        }
    }

    static class ObuFrameHeader {
        int display_frame_id;
        int current_frame_id;
        long frame_offset;
        int[] expected_frame_id = new int[7];
        int width;
        int height;
        int columns4x4;
        int rows4x4;
        int render_width;
        int render_height;
        int upscaled_width;
        LoopRestoration loop_restoration = new LoopRestoration();
        int[] buffer_removal_time = new int[32];
        int frame_presentation_time;
        GlobalMotion[] global_motion = new GlobalMotion[]{new GlobalMotion(), new GlobalMotion(), new GlobalMotion(), new GlobalMotion(), new GlobalMotion(), new GlobalMotion(), new GlobalMotion(), new GlobalMotion()};
        TileInfo tile_info = new TileInfo();
        QuantizerParameters quantizer = new QuantizerParameters();
        Segmentation segmentation = new Segmentation();
        boolean show_existing_frame;
        int frame_to_show;
        int frame_type;
        boolean show_frame;
        boolean showable_frame;
        boolean error_resilient_mode;
        boolean enable_cdf_update;
        boolean frame_size_override_flag;
        int order_hint;
        int primary_reference_frame;
        boolean render_and_frame_size_different;
        boolean use_superres;
        int superres_scale_denominator;
        boolean allow_screen_content_tools;
        boolean allow_intrabc;
        boolean frame_refs_short_signaling;
        int refresh_frame_flags;
        boolean found_reference;
        int force_integer_mv;
        boolean allow_high_precision_mv;
        int interpolation_filter;
        boolean is_motion_mode_switchable;
        boolean use_ref_frame_mvs;
        boolean enable_frame_end_update_cdf;
        boolean coded_lossless;
        boolean upscaled_lossless;
        int tx_mode;
        boolean reference_mode_select;
        int[] skip_mode_frame = new int[2];
        boolean skip_mode_present;
        boolean reduced_tx_set;
        boolean allow_warped_motion;
        Delta delta_q = new Delta();
        Delta delta_lf = new Delta();
        int[] reference_frame_index = new int[7];
        int[] reference_order_hint = new int[8];
        LoopFilter loop_filter = new LoopFilter();
        Cdef cdef = new Cdef();
        FilmGrainParams film_grain_params;

        ObuFrameHeader() {
        }

        public String toString() {
            return "ObuFrameHeader{display_frame_id=" + this.display_frame_id + "\ncurrent_frame_id=" + this.current_frame_id + "\nframe_offset=" + this.frame_offset + "\nexpected_frame_id=" + Arrays.toString(this.expected_frame_id) + "\nwidth=" + this.width + "\nheight=" + this.height + "\ncolumns4x4=" + this.columns4x4 + "\nrows4x4=" + this.rows4x4 + "\nrender_width=" + this.render_width + "\nrender_height=" + this.render_height + "\nupscaled_width=" + this.upscaled_width + "\nloop_restoration=" + String.valueOf(this.loop_restoration) + "\nbuffer_removal_time=" + Arrays.toString(this.buffer_removal_time) + "\nframe_presentation_time=" + this.frame_presentation_time + "\nglobal_motion=" + Arrays.toString(this.global_motion) + "\ntile_info=" + String.valueOf(this.tile_info) + "\nquantizer=" + String.valueOf(this.quantizer) + "\nsegmentation=" + String.valueOf(this.segmentation) + "\nshow_existing_frame=" + this.show_existing_frame + "\nframe_to_show=" + this.frame_to_show + "\nframe_type=" + this.frame_type + "\nshow_frame=" + this.show_frame + "\nshowable_frame=" + this.showable_frame + "\nerror_resilient_mode=" + this.error_resilient_mode + "\nenable_cdf_update=" + this.enable_cdf_update + "\nframe_size_override_flag=" + this.frame_size_override_flag + "\norder_hint=" + this.order_hint + "\nprimary_reference_frame=" + this.primary_reference_frame + "\nrender_and_frame_size_different=" + this.render_and_frame_size_different + "\nuse_superres=" + this.use_superres + "\nsuperres_scale_denominator=" + this.superres_scale_denominator + "\nallow_screen_content_tools=" + this.allow_screen_content_tools + "\nallow_intrabc=" + this.allow_intrabc + "\nframe_refs_short_signaling=" + this.frame_refs_short_signaling + "\nrefresh_frame_flags=" + this.refresh_frame_flags + "\nfound_reference=" + this.found_reference + "\nforce_integer_mv=" + this.force_integer_mv + "\nallow_high_precision_mv=" + this.allow_high_precision_mv + "\ninterpolation_filter=" + this.interpolation_filter + "\nis_motion_mode_switchable=" + this.is_motion_mode_switchable + "\nuse_ref_frame_mvs=" + this.use_ref_frame_mvs + "\nenable_frame_end_update_cdf=" + this.enable_frame_end_update_cdf + "\ncoded_lossless=" + this.coded_lossless + "\nupscaled_lossless=" + this.upscaled_lossless + "\ntx_mode=" + this.tx_mode + "\nreference_mode_select=" + this.reference_mode_select + "\nskip_mode_frame=" + Arrays.toString(this.skip_mode_frame) + "\nskip_mode_present=" + this.skip_mode_present + "\nreduced_tx_set=" + this.reduced_tx_set + "\nallow_warped_motion=" + this.allow_warped_motion + "\ndelta_q=" + String.valueOf(this.delta_q) + "\ndelta_lf=" + String.valueOf(this.delta_lf) + "\nreference_frame_index=" + Arrays.toString(this.reference_frame_index) + "\nreference_order_hint=" + Arrays.toString(this.reference_order_hint) + "\nloop_filter=" + String.valueOf(this.loop_filter) + "\ncdef=" + String.valueOf(this.cdef) + "\nfilm_grain_params=" + String.valueOf(this.film_grain_params) + "}";
        }
    }

    static class FilmGrainParams {
        boolean apply_grain;
        boolean update_grain;
        boolean chroma_scaling_from_luma;
        boolean overlap_flag;
        boolean clip_to_restricted_range;
        int num_y_points;
        int num_u_points;
        int num_v_points;
        int[] point_y_value = new int[14];
        int[] point_y_scaling = new int[14];
        int[] point_u_value = new int[10];
        int[] point_u_scaling = new int[10];
        int[] point_v_value = new int[10];
        int[] point_v_scaling = new int[10];
        int chroma_scaling;
        int auto_regression_coeff_lag;
        int[] auto_regression_coeff_y = new int[24];
        int[] auto_regression_coeff_u = new int[25];
        int[] auto_regression_coeff_v = new int[25];
        int auto_regression_shift;
        int grain_seed;
        int reference_index;
        int grain_scale_shift;
        int u_multiplier;
        int u_luma_multiplier;
        int u_offset;
        int v_multiplier;
        int v_luma_multiplier;
        int v_offset;

        FilmGrainParams() {
        }
    }

    static class Segmentation {
        boolean enabled;
        boolean update_map;
        boolean update_data;
        boolean temporal_update;
        boolean segment_id_pre_skip;
        int last_active_segment_id;
        boolean[][] feature_enabled = new boolean[8][8];
        int[][] feature_data = new int[8][8];
        boolean[] lossless = new boolean[8];
        int[] qindex = new int[8];

        Segmentation() {
        }

        boolean FeatureActive(int segment_id, int feature) {
            return this.enabled && segment_id < 8 && this.feature_enabled[segment_id][feature];
        }

        static boolean FeatureSigned(int feature) {
            return feature <= 4;
        }

        public String toString() {
            return "Segmentation{\n\t enabled=" + this.enabled + "\n\t update_map=" + this.update_map + "\n\t update_data=" + this.update_data + "\n\t temporal_update=" + this.temporal_update + "\n\t segment_id_pre_skip=" + this.segment_id_pre_skip + "\n\t last_active_segment_id=" + this.last_active_segment_id + "\n\t feature_enabled=" + Arrays.toString((Object[])this.feature_enabled) + "\n\t feature_data=" + Arrays.toString((Object[])this.feature_data) + "\n\t lossless=" + Arrays.toString(this.lossless) + "\n\t qindex=" + Arrays.toString(this.qindex) + "}";
        }
    }

    static class QuantizerParameters {
        int base_index;
        int[] delta_dc = new int[3];
        int[] delta_ac = new int[3];
        boolean use_matrix;
        int[] matrix_level = new int[3];

        QuantizerParameters() {
        }

        public String toString() {
            return "QuantizerParameters{\n\t base_index=" + this.base_index + "\n\t delta_dc=" + Arrays.toString(this.delta_dc) + "\n\t delta_ac=" + Arrays.toString(this.delta_ac) + "\n\t use_matrix=" + this.use_matrix + "\n\t matrix_level=" + Arrays.toString(this.matrix_level) + "}";
        }
    }

    static class LoopRestoration {
        int[] type = new int[3];
        int[] unit_size_log2 = new int[3];

        LoopRestoration() {
        }
    }

    static class TileInfo {
        boolean uniform_spacing;
        int sb_rows;
        int sb_columns;
        int tile_count;
        int tile_columns_log2;
        int tile_columns;
        int[] tile_column_start = new int[65];
        int[] tile_column_width_in_superblocks = new int[65];
        int tile_rows_log2;
        int tile_rows;
        int[] tile_row_start = new int[65];
        int[] tile_row_height_in_superblocks = new int[65];
        int context_update_id;
        int tile_size_bytes;

        TileInfo() {
        }

        public String toString() {
            return "TileInfo{\n\t uniform_spacing=" + this.uniform_spacing + "\n\t sb_rows=" + this.sb_rows + "\n\t sb_columns=" + this.sb_columns + "\n\t tile_count=" + this.tile_count + "\n\t tile_columns_log2=" + this.tile_columns_log2 + "\n\t tile_columns=" + this.tile_columns + "\n\t tile_column_start=" + Arrays.toString(this.tile_column_start) + "\n\t tile_column_width_in_superblocks=" + Arrays.toString(this.tile_column_width_in_superblocks) + "\n\t tile_rows_log2=" + this.tile_rows_log2 + "\n\t tile_rows=" + this.tile_rows + "\n\t tile_row_start=" + Arrays.toString(this.tile_row_start) + "\n\t tile_row_height_in_superblocks=" + Arrays.toString(this.tile_row_height_in_superblocks) + "\n\t context_update_id=" + this.context_update_id + "\n\t tile_size_bytes=" + this.tile_size_bytes + "}";
        }
    }

    static class Cdef {
        int damping;
        int bits;
        int[] y_primary_strength = new int[8];
        int[] y_secondary_strength = new int[8];
        int[] uv_primary_strength = new int[8];
        int[] uv_secondary_strength = new int[8];

        Cdef() {
        }
    }

    static class Delta {
        boolean present;
        int scale;
        boolean multi;

        Delta() {
        }
    }

    static class LoopFilter {
        int[] level = new int[4];
        int sharpness;
        boolean delta_enabled;
        boolean delta_update;
        int[] ref_deltas = new int[8];
        int[] mode_deltas = new int[2];

        LoopFilter() {
        }
    }

    static class GlobalMotion {
        int type;
        int[] params = new int[6];
        int alpha;
        int beta;
        int gamma;
        int delta;

        GlobalMotion() {
        }
    }

    static class BlockCdfContext {
        boolean[] use_predicted_segment_id = new boolean[32];
        boolean[] is_explicit_compound_type = new boolean[32];
        boolean[] is_compound_type_average = new boolean[32];
        boolean[] skip_mode = new boolean[32];
        int[][] palette_size = new int[2][32];
        int[][][] palette_color = new int[32][2][8];
        int[] uv_mode = new int[32];

        BlockCdfContext() {
        }
    }

    static class BlockParams {
        int pos;
        int size;
        boolean skip = true;
        boolean is_inter = true;
        int y_mode;
        int uv_transform_size;
        int[] interpolation_filter = new int[2];
        int[] reference_frame = new int[2];
        int[] deblock_filter_level = new int[4];
        CompoundMotionVector mv = new CompoundMotionVector();
        PredictionParams prediction_parameters = null;

        BlockParams() {
        }
    }

    static class PredictionParams {
        boolean use_filter_intra;
        int filter_intra_mode;
        int[] angle_delta = new int[2];
        int cfl_alpha_u;
        int cfl_alpha_v;
        int max_luma_width;
        int max_luma_height;
        int[][][] color_index_map = new int[2][][];
        boolean use_intra_block_copy;
        int inter_intra_mode;
        boolean is_wedge_inter_intra;
        int wedge_index;
        int wedge_sign;
        boolean mask_is_inverse;
        int motion_mode;
        int compound_prediction_type;
        MotionVector[] ref_mv_stack = new MotionVector[]{new MotionVector(), new MotionVector(), new MotionVector(), new MotionVector(), new MotionVector(), new MotionVector(), new MotionVector(), new MotionVector()};
        CompoundMotionVector[] compound_ref_mv_stack = new CompoundMotionVector[]{new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector(), new CompoundMotionVector()};
        int[] weight_index_stack = new int[8];
        int nearest_mv_count;
        int ref_mv_count;
        int ref_mv_index;
        MotionVector[] global_mv = new MotionVector[]{new MotionVector(), new MotionVector()};
        int num_warp_samples;
        int[][] warp_estimate_candidates = new int[8][4];
        PaletteModeInfo palette_mode_info = new PaletteModeInfo();
        int segment_id;
        int uv_mode;
        boolean chroma_top_uses_smooth_prediction;
        boolean chroma_left_uses_smooth_prediction;

        PredictionParams() {
        }

        MotionVector reference_mv(int stack_index) {
            return this.ref_mv_stack[7 - (this.weight_index_stack[stack_index] & 7)];
        }

        MotionVector reference_mv(int stack_index, int mv_index) {
            return this.compound_ref_mv_stack[7 - (this.weight_index_stack[stack_index] & 7)].mv[mv_index];
        }

        void IncreaseWeight(int index, int weight) {
            int n = index;
            this.weight_index_stack[n] = this.weight_index_stack[n] + (weight << 3);
        }

        void SetWeightIndexStackEntry(int index, int weight) {
            this.weight_index_stack[index] = (weight << 3) + 7 - index;
        }
    }

    static class PaletteModeInfo {
        int[] size = new int[2];
        int[][] color = new int[3][8];

        PaletteModeInfo() {
        }
    }

    static class MvContexts {
        int zero_mv;
        int[] reference_mv = new int[]{0};
        int[] new_mv = new int[]{0};

        MvContexts() {
        }
    }

    static class TemporalMotionField {
        MotionVector[][] mv;
        int[][] reference_offset;

        TemporalMotionField() {
        }
    }

    static class CompoundMotionVector {
        MotionVector[] mv = new MotionVector[]{new MotionVector(), new MotionVector()};
        long mv64;

        CompoundMotionVector() {
        }
    }

    static class MotionVector {
        int[] mv = new int[2];
        int mv32;

        MotionVector() {
        }
    }

    static class TileBuffer {
        byte[] data;
        int offset;
        int size;

        TileBuffer(byte[] data, int offset, int size) {
            this.data = data;
            this.offset = offset;
            this.size = size;
        }
    }

    static class FrameScratchBuffer {
        LoopRestorationInfo loop_restoration_info = new LoopRestorationInfo();
        Array2DView cdef_index = new Array2DView();
        Array2DView cdef_skip = new Array2DView();
        int[][] inter_transform_sizes;
        BlockParamsHolder block_parameters_holder = new BlockParamsHolder();
        TemporalMotionField motion_field;
        Symbol symbol_decoder_context = new Symbol();
        ResidualBufferPool residual_buffer_pool;
        Yuv cdef_border = new Yuv();
        int[] superres_coefficients = new int[2];
        Yuv superres_line_buffer = new Yuv();
        Yuv loop_restoration_border = new Yuv();
        AlignedDynamicBufferIntraPredictionBuffer intra_prediction_buffers = new AlignedDynamicBufferIntraPredictionBuffer();
        TileScratchBufferPool tile_scratch_buffer_pool = new TileScratchBufferPool();
        ThreadingStrategy threading_strategy = new ThreadingStrategy();
        boolean tile_decoding_failed;

        FrameScratchBuffer() {
        }
    }

    static class DynamicBufferRestorationUnitInfo {
        RestorationUnitInfo[] buffer_;

        DynamicBufferRestorationUnitInfo() {
        }

        RestorationUnitInfo[] get() {
            return this.buffer_;
        }

        boolean Resize(int totalNumUnits) {
            this.buffer_ = new RestorationUnitInfo[totalNumUnits];
            for (int i = 0; i < totalNumUnits; ++i) {
                this.buffer_[i] = new RestorationUnitInfo();
            }
            return true;
        }
    }

    static class DynamicBufferBlockCdfContext {
        BlockCdfContext[] buffer_;

        DynamicBufferBlockCdfContext() {
        }

        BlockCdfContext[] get() {
            return this.buffer_;
        }

        public boolean Resize(int totalNumUnits) {
            if (this.buffer_ != null) {
                LogWriter.writeLog("BlockCdfContext not null");
            }
            this.buffer_ = new BlockCdfContext[totalNumUnits];
            for (int i = 0; i < totalNumUnits; ++i) {
                this.buffer_[i] = new BlockCdfContext();
            }
            return true;
        }
    }

    static class AlignedDynamicBufferIntraPredictionBuffer {
        IntraPredictionBuffer[][] buffer_ = new IntraPredictionBuffer[32][32];

        IntraPredictionBuffer[][] get() {
            return this.buffer_;
        }

        AlignedDynamicBufferIntraPredictionBuffer() {
            for (int i = 0; i < 32; ++i) {
                for (int j = 0; j < 32; ++j) {
                    this.buffer_[i][j] = new IntraPredictionBuffer();
                }
            }
        }
    }

    static class IntraPredictionBuffer {
        int[] buffer_;

        IntraPredictionBuffer() {
        }

        int[] get() {
            return this.buffer_;
        }

        boolean Resize(int size) {
            if (this.buffer_ != null) {
                LogWriter.writeLog("D: buffer not null");
            }
            this.buffer_ = new int[size];
            return true;
        }
    }

    static class BlockParamsHolder {
        private int rows4x4_ = 0;
        private int columns4x4_ = 0;
        private int index_;
        ArrayList<BlockParams> block_parameters_ = new ArrayList();
        BlockParams[] block_parameters_cache_;

        BlockParamsHolder() {
        }

        int columns4x4() {
            return this.columns4x4_;
        }

        BlockParams Find(int row, int column) {
            return this.block_parameters_cache_[row * this.columns4x4_ + column];
        }

        BlockParams findByIndex(int n) {
            return this.block_parameters_.get(n);
        }

        boolean Reset(int rows4x4, int columns4x4) {
            this.rows4x4_ = rows4x4;
            this.columns4x4_ = columns4x4;
            this.index_ = 0;
            int size = this.rows4x4_ * this.columns4x4_;
            this.block_parameters_cache_ = new BlockParams[size];
            for (int i = 0; i < size; ++i) {
                BlockParams bp = new BlockParams();
                bp.pos = i;
                this.block_parameters_.add(bp);
                this.block_parameters_cache_[i] = bp;
            }
            return true;
        }

        BlockParams Get(int row4x4, int column4x4, int block_size) {
            int index;
            if ((index = this.index_++) >= this.block_parameters_.size()) {
                return null;
            }
            BlockParams bp = this.block_parameters_.get(index);
            this.FillCache(row4x4, column4x4, block_size, bp);
            return bp;
        }

        void FillCache(int row4x4, int column4x4, int block_size, BlockParams bp) {
            int rows = Math.min(kNum4x4BlocksHigh[block_size], this.rows4x4_ - row4x4);
            int columns = Math.min(kNum4x4BlocksWide[block_size], this.columns4x4_ - column4x4);
            int bp_dst = row4x4 * this.columns4x4_ + column4x4;
            if (columns == 1) {
                D.SetBlock(rows, 1, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else if (columns == 2) {
                D.SetBlock(rows, 2, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else if (columns == 4) {
                D.SetBlock(rows, 4, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else if (columns == 8) {
                D.SetBlock(rows, 8, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else if (columns == 16) {
                D.SetBlock(rows, 16, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else if (columns == 32) {
                D.SetBlock(rows, 32, bp, bp_dst, this.columns4x4_, this.block_parameters_cache_);
            } else {
                do {
                    int x = columns;
                    int d = bp_dst;
                    do {
                        this.block_parameters_cache_[d++] = bp;
                    } while (--x != 0);
                    bp_dst += this.columns4x4_;
                } while (--rows != 0);
            }
        }
    }

    static class ObuSequenceHeader {
        boolean ParametersChanged;
        int profile;
        boolean still_picture;
        boolean reduced_still_picture_header;
        int operating_points;
        int[] operating_point_idc = new int[32];
        BitStreamLevel[] level = new BitStreamLevel[32];
        int[] tier = new int[32];
        int frame_width_bits;
        int frame_height_bits;
        int max_frame_width;
        int max_frame_height;
        boolean frame_id_numbers_present;
        int frame_id_length_bits;
        int delta_frame_id_length_bits;
        boolean use_128x128_superblock;
        boolean enable_filter_intra;
        boolean enable_intra_edge_filter;
        boolean enable_interintra_compound;
        boolean enable_masked_compound;
        boolean enable_warped_motion;
        boolean enable_dual_filter;
        boolean enable_order_hint;
        int order_hint_bits;
        int order_hint_shift_bits;
        boolean enable_jnt_comp;
        boolean enable_ref_frame_mvs;
        boolean choose_screen_content_tools;
        int force_screen_content_tools;
        boolean choose_integer_mv;
        int force_integer_mv;
        boolean enable_superres;
        boolean enable_cdef;
        boolean enable_restoration;
        ColorConfig color_config = new ColorConfig();
        boolean timing_info_present_flag;
        TimingInfo timing_info = new TimingInfo();
        boolean decoder_model_info_present_flag;
        DecoderModelInfo decoder_model_info = new DecoderModelInfo();
        boolean[] decoder_model_present_for_operating_point = new boolean[32];
        boolean initial_display_delay_present_flag;
        int[] initial_display_delay = new int[32];
        boolean film_grain_params_present;
        OperatingParameters operating_parameters = new OperatingParameters();

        ObuSequenceHeader() {
            for (int i = 0; i < 32; ++i) {
                this.level[i] = new BitStreamLevel();
            }
        }

        boolean ParametersChanged(ObuSequenceHeader old) {
            OperatingParameters cur = this.operating_parameters;
            OperatingParameters prev = old.operating_parameters;
            int totalLen = cur.decoder_buffer_delay.length;
            for (int i = 0; i < totalLen; ++i) {
                if (cur.decoder_buffer_delay[i] != prev.decoder_buffer_delay[i]) {
                    return true;
                }
                if (cur.encoder_buffer_delay[i] != prev.encoder_buffer_delay[i]) {
                    return true;
                }
                if (cur.low_delay_mode_flag[i] == prev.low_delay_mode_flag[i]) continue;
                return true;
            }
            return false;
        }

        public String toString() {
            return "ObuSequenceHeader{ParametersChanged=" + this.ParametersChanged + "\n profile=" + this.profile + "\n still_picture=" + this.still_picture + "\n reduced_still_picture_header=" + this.reduced_still_picture_header + "\n operating_points=" + this.operating_points + "\n operating_point_idc=" + Arrays.toString(this.operating_point_idc) + "\n level=" + Arrays.toString(this.level) + "\n tier=" + Arrays.toString(this.tier) + "\n frame_width_bits=" + this.frame_width_bits + "\n frame_height_bits=" + this.frame_height_bits + "\n max_frame_width=" + this.max_frame_width + "\n max_frame_height=" + this.max_frame_height + "\n frame_id_numbers_present=" + this.frame_id_numbers_present + "\n frame_id_length_bits=" + this.frame_id_length_bits + "\n delta_frame_id_length_bits=" + this.delta_frame_id_length_bits + "\n use_128x128_superblock=" + this.use_128x128_superblock + "\n enable_filter_intra=" + this.enable_filter_intra + "\n enable_intra_edge_filter=" + this.enable_intra_edge_filter + "\n enable_interintra_compound=" + this.enable_interintra_compound + "\n enable_masked_compound=" + this.enable_masked_compound + "\n enable_warped_motion=" + this.enable_warped_motion + "\n enable_dual_filter=" + this.enable_dual_filter + "\n enable_order_hint=" + this.enable_order_hint + "\n order_hint_bits=" + this.order_hint_bits + "\n order_hint_shift_bits=" + this.order_hint_shift_bits + "\n enable_jnt_comp=" + this.enable_jnt_comp + "\n enable_ref_frame_mvs=" + this.enable_ref_frame_mvs + "\n choose_screen_content_tools=" + this.choose_screen_content_tools + "\n force_screen_content_tools=" + this.force_screen_content_tools + "\n choose_integer_mv=" + this.choose_integer_mv + "\n force_integer_mv=" + this.force_integer_mv + "\n enable_superres=" + this.enable_superres + "\n enable_cdef=" + this.enable_cdef + "\n enable_restoration=" + this.enable_restoration + "\n color_config=" + String.valueOf(this.color_config) + "\n timing_info_present_flag=" + this.timing_info_present_flag + "\n timing_info=" + String.valueOf(this.timing_info) + "\n decoder_model_info_present_flag=" + this.decoder_model_info_present_flag + "\n decoder_model_info=" + String.valueOf(this.decoder_model_info) + "\n decoder_model_present_for_operating_point=" + Arrays.toString(this.decoder_model_present_for_operating_point) + "\n initial_display_delay_present_flag=" + this.initial_display_delay_present_flag + "\n initial_display_delay=" + Arrays.toString(this.initial_display_delay) + "\n film_grain_params_present=" + this.film_grain_params_present + "\n operating_parameters=" + String.valueOf(this.operating_parameters) + "}";
        }
    }

    static class OperatingParameters {
        int[] decoder_buffer_delay = new int[32];
        int[] encoder_buffer_delay = new int[32];
        boolean[] low_delay_mode_flag = new boolean[32];

        OperatingParameters() {
        }
    }

    static class DecoderModelInfo {
        int encoder_decoder_buffer_delay_length;
        int num_units_in_decoding_tick;
        int buffer_removal_time_length;
        int frame_presentation_time_length;

        DecoderModelInfo() {
        }
    }

    static class TimingInfo {
        int num_units_in_tick;
        int time_scale;
        boolean equal_picture_interval;
        int num_ticks_per_picture;

        TimingInfo() {
        }
    }

    static class ColorConfig {
        int bitdepth;
        boolean is_monochrome;
        int color_primary;
        int transfer_characteristics;
        int matrix_coefficients;
        int color_range;
        int subsampling_x;
        int subsampling_y;
        int chroma_sample_position;
        boolean separate_uv_delta_q;

        ColorConfig() {
        }

        public String toString() {
            return "ColorConfig{bitdepth: " + this.bitdepth + ", is_monochrome: " + this.is_monochrome + ", color_primary: " + this.color_primary + ", transfer_characteristics: " + this.transfer_characteristics + ", matrix_coefficients: " + this.matrix_coefficients + ", color_range: " + this.color_range + ", subsampling_x: " + this.subsampling_x + ", subsampling_y: " + this.subsampling_y + ", chroma_sample_position: " + this.chroma_sample_position + ", separate_uv_delta_q: " + this.separate_uv_delta_q + "}";
        }
    }

    static class BitStreamLevel {
        int major;
        int minor;

        BitStreamLevel() {
        }
    }

    static class ObuHeader {
        int type;
        boolean has_extension;
        boolean has_size_field;
        int temporal_id;
        int spatial_id;

        ObuHeader() {
        }

        public String toString() {
            return "ObuHeader{type=" + this.type + ", has_extension=" + this.has_extension + ", has_size_field=" + this.has_size_field + ", temporal_id=" + this.temporal_id + ", spatial_id=" + this.spatial_id + "}";
        }
    }

    static class WarpFilterParams {
        long x4;
        long y4;
        int ix4;
        int iy4;

        WarpFilterParams() {
        }
    }

    static class RestorationBuffer {
        SgrBuffer sgr_buffer = new SgrBuffer();
        int[] wiener_buffer = new int[17920];

        RestorationBuffer() {
        }
    }

    static class SgrBuffer {
        int[] sum3 = new int[1152];
        int[] sum5 = new int[1440];
        int[] square_sum3 = new int[1152];
        int[] square_sum5 = new int[1440];
        int[] ma343 = new int[1024];
        int[] ma444 = new int[768];
        int[] ma565 = new int[512];
        int[] b343 = new int[1024];
        int[] b444 = new int[768];
        int[] b565 = new int[512];
        int[] ma = new int[288];
        int[] b = new int[288];

        SgrBuffer() {
        }
    }

    static class RestorationUnitInfo {
        int type;
        SgrProjInfo sgr_proj_info = new SgrProjInfo();
        WienerInfo wiener_info = new WienerInfo();

        RestorationUnitInfo() {
        }
    }

    static class WienerInfo {
        static final int kVertical = 0;
        static final int kHorizontal = 1;
        int[] number_leading_zero_coefficients = new int[2];
        int[][] filter = new int[2][4];

        WienerInfo() {
        }
    }

    static class SgrProjInfo {
        int index;
        int[] multiplier = new int[2];

        SgrProjInfo() {
        }
    }
}