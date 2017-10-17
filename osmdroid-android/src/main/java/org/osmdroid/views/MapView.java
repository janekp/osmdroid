// Created by plusminus on 17:45:56 - 25.09.2008
package org.osmdroid.views;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.metalev.multitouch.controller.MultiTouchController;
import org.metalev.multitouch.controller.MultiTouchController.MultiTouchObjectCanvas;
import org.metalev.multitouch.controller.MultiTouchController.PointInfo;
import org.metalev.multitouch.controller.MultiTouchController.PositionAndScale;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.api.IMapView;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.MapTileProviderArray;
import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.IStyledTileSource;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleInvalidationHandler;
import org.osmdroid.util.BoundingBoxE6;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.GeometryMath;
import org.osmdroid.util.PointL;
import org.osmdroid.util.RectL;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.overlay.DefaultOverlayManager;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.OverlayManager;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.ZoomButtonsOverlay;
import org.osmdroid.views.util.constants.MapViewConstants;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * This is the primary view for osmdroid
 * 
 * @since the begining
 */
public class MapView extends ViewGroup implements IMapView, MapViewConstants,
		MultiTouchObjectCanvas<Object> {

	// ===========================================================
	// Constants
	// ===========================================================

	private static final double ZOOM_SENSITIVITY = 1.0;
	private static final double ZOOM_LOG_BASE_INV = 1.0 / Math.log(2.0 / ZOOM_SENSITIVITY);
	private static Method sMotionEventTransformMethod;

	// ===========================================================
	// Fields
	// ===========================================================

	/** Current zoom level for map tiles. */
	private double mZoomLevel = 0;

	private OverlayManager mOverlayManager;

	protected Projection mProjection;

	private TilesOverlay mMapOverlay;

	private final GestureDetector mGestureDetector;

	/** Handles map scrolling */
	private final Scroller mScroller;
	protected boolean mIsFlinging;

	protected final AtomicReference<Double> mTargetZoomLevel = new AtomicReference<>();
	protected final AtomicBoolean mIsAnimating = new AtomicBoolean(false);

	protected Integer mMinimumZoomLevel;
	protected Integer mMaximumZoomLevel;

	private final MapController mController;

	private MultiTouchController<Object> mMultiTouchController;
	protected float mMultiTouchScale = 1.0f;
	protected PointF mMultiTouchScalePoint = new PointF();

	protected MapListener mListener;

	// For rotation
	private float mapOrientation = 0;
	private final Rect mInvalidateRect = new Rect();

	protected BoundingBox mScrollableAreaBoundingBox;
	protected RectL mScrollableAreaLimit;

	private MapTileProviderBase mTileProvider;
	private Handler mTileRequestCompleteHandler;
	private boolean mTilesScaledToDpi = false;

	final Point mRotateScalePoint = new Point();

	/* a point that will be reused to lay out added views */
	private final Point mLayoutPoint = new Point();
	private final PointL mMercatorPoint = new PointL();

	// Keep a set of listeners for when the maps have a layout
	private final LinkedList<OnFirstLayoutListener> mOnFirstLayoutListeners = new LinkedList<MapView.OnFirstLayoutListener>();
	
	/* becomes true once onLayout has been called for the first time i.e. map is ready to go. */
	private boolean mLayoutOccurred = false;

	private GeoPoint mCenter;
	private long mMapScrollX;
	private long mMapScrollY;

	private ZoomButtonsOverlay mZoomButtonsOverlay;

	public interface OnFirstLayoutListener {
		/**
		 * this generally means that the map is ready to go
		 * @param v
		 * @param left
		 * @param top
         * @param right
         * @param bottom
         */
		void onFirstLayout(View v, int left, int top, int right, int bottom);
	}

	// ===========================================================
	// Constructors
	// ===========================================================

	public MapView(final Context context,
					  MapTileProviderBase tileProvider,
					  final Handler tileRequestCompleteHandler, final AttributeSet attrs) {
		this(context, tileProvider, tileRequestCompleteHandler, attrs, Configuration.getInstance().isMapViewHardwareAccelerated());

	}
	
	public MapView(final Context context,
					  MapTileProviderBase tileProvider,
					  final Handler tileRequestCompleteHandler, final AttributeSet attrs, boolean hardwareAccelerated) {
		super(context, attrs);
		if(isInEditMode()){ 	//fix for edit mode in the IDE
			mTileRequestCompleteHandler=null;
			mController=null;
			mScroller=null;
			mGestureDetector=null;
			return;
		}
		if (!hardwareAccelerated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			this.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}
		this.mController = new MapController(this);
		this.mScroller = new Scroller(context);

		if (tileProvider == null) {
			final ITileSource tileSource = getTileSourceFromAttributes(attrs);
			tileProvider = isInEditMode()
					? new MapTileProviderArray(tileSource, null, new MapTileModuleProviderBase[0])
					: new MapTileProviderBasic(context.getApplicationContext(), tileSource);
		}

		mTileRequestCompleteHandler = tileRequestCompleteHandler == null
				? new SimpleInvalidationHandler(this)
				: tileRequestCompleteHandler;
		mTileProvider = tileProvider;
		mTileProvider.setTileRequestCompleteHandler(mTileRequestCompleteHandler);
		updateTileSizeForDensity(mTileProvider.getTileSource());

		this.mMapOverlay = new TilesOverlay(mTileProvider, context);
		mOverlayManager = new DefaultOverlayManager(mMapOverlay);

		mGestureDetector = new GestureDetector(context, new MapViewGestureDetectorListener());
		mGestureDetector.setOnDoubleTapListener(new MapViewDoubleClickListener());

		/*
		fix for map in recycler views
		see https://github.com/osmdroid/osmdroid/issues/588
		https://github.com/osmdroid/osmdroid/issues/568
		 */
		if (Configuration.getInstance().isMapViewRecyclerFriendly())
		if (Build.VERSION.SDK_INT >= 16)
			this.setHasTransientState(true);

		mZoomButtonsOverlay = new ZoomButtonsOverlay(this);
		mZoomButtonsOverlay.setEnabled(false);
	}

	/**
	 * Constructor used by XML layout resource (uses default tile source).
	 */
	public MapView(final Context context, final AttributeSet attrs) {
		this(context, null, null, attrs);
	}

	public MapView(final Context context) {
		this(context, null, null, null);
	}




	public MapView(final Context context,
				   final MapTileProviderBase aTileProvider) {
		this(context, aTileProvider, null);
	}

	public MapView(final Context context,
				   final MapTileProviderBase aTileProvider,
				   final Handler tileRequestCompleteHandler) {
		this(context, aTileProvider, tileRequestCompleteHandler,
				null);
	}

	// ===========================================================
	// Getter & Setter
	// ===========================================================

	@Override
	public IMapController getController() {
		return this.mController;
	}

	/**
	 * You can add/remove/reorder your Overlays using the List of {@link Overlay}. The first (index
	 * 0) Overlay gets drawn first, the one with the highest as the last one.
	 */
	public List<Overlay> getOverlays() {
		return this.getOverlayManager().overlays();
	}

	public OverlayManager getOverlayManager() {
		return mOverlayManager;
	}

	public void setOverlayManager(final OverlayManager overlayManager) {
		mOverlayManager = overlayManager;
	}

	public MapTileProviderBase getTileProvider() {
		return mTileProvider;
	}

	public Scroller getScroller() {
		return mScroller;
	}

	public Handler getTileRequestCompleteHandler() {
		return mTileRequestCompleteHandler;
	}

	@Override
	public int getLatitudeSpan() {
		return this.getBoundingBoxE6().getLatitudeSpanE6();
     }
	public double getLatitudeSpanDouble() {
		return this.getBoundingBox().getLatitudeSpan();
	}

	@Override
	public int getLongitudeSpan() {
		return this.getBoundingBoxE6().getLongitudeSpanE6();
     }
	public double getLongitudeSpanDouble() {
		return this.getBoundingBox().getLongitudeSpan();
	}

	public BoundingBoxE6 getBoundingBoxE6() {
          return getProjection().getBoundingBoxE6();
     }
	public BoundingBox getBoundingBox() {
		return getProjection().getBoundingBox();
	}

	/**
	 * Gets the current bounds of the screen in <I>screen coordinates</I>.
	 */
	public Rect getScreenRect(final Rect reuse) {
		final Rect out = getIntrinsicScreenRect(reuse);
		if (this.getMapOrientation() != 0 && this.getMapOrientation() != 180) {
			GeometryMath.getBoundingBoxForRotatatedRectangle(out, out.centerX(), out.centerY(),
					this.getMapOrientation(), out);
		}
		return out;
	}

	public Rect getIntrinsicScreenRect(final Rect reuse) {
		final Rect out = reuse == null ? new Rect() : reuse;
		out.set(0, 0, getWidth(), getHeight());
		return out;
	}

	/**
	 * Get a projection for converting between screen-pixel coordinates and latitude/longitude
	 * coordinates. You should not hold on to this object for more than one draw, since the
	 * projection of the map could change.
	 *
	 * @return The Projection of the map in its current state. You should not hold on to this object
	 *         for more than one draw, since the projection of the map could change.
	 */
	@Override
	public Projection getProjection() {
		if (mProjection == null) {
			mProjection = new Projection(this);
		}
		return mProjection;
	}

	/**
	 * Use {@link #resetProjection()} instead
	 * @param p
	 */
	@Deprecated
	protected void setProjection(Projection p){
		mProjection = p;
	}

	private void resetProjection(){
		mProjection = null;
	}

	/**
	 * @deprecated use {@link IMapController#animateTo(IGeoPoint)} or {@link IMapController#setCenter(IGeoPoint)} instead
	 */
	@Deprecated
	void setMapCenter(final IGeoPoint aCenter) {
		getController().animateTo(aCenter);
	}

	/**
	 * @deprecated use {@link #setMapCenter(IGeoPoint)}
	 */
	@Deprecated
	void setMapCenter(final int aLatitudeE6, final int aLongitudeE6) {
		setMapCenter(new GeoPoint(aLatitudeE6, aLongitudeE6));
     }
     @Deprecated
	void setMapCenter(final double aLatitude, final double aLongitude) {
		setMapCenter(new GeoPoint(aLatitude, aLongitude));
	}

	public boolean isTilesScaledToDpi() {
		return mTilesScaledToDpi;
	}

	public void setTilesScaledToDpi(boolean tilesScaledToDpi) {
		mTilesScaledToDpi = tilesScaledToDpi;
		updateTileSizeForDensity(getTileProvider().getTileSource());
	}

	private void updateTileSizeForDensity(final ITileSource aTileSource) {
		int tile_size = aTileSource.getTileSizePixels();
		float density =  getResources().getDisplayMetrics().density * 256 / tile_size ;
		int size = (int) ( tile_size * (isTilesScaledToDpi() ? density : 1));
		if (Configuration.getInstance().isDebugMapView())
			Log.d(IMapView.LOGTAG, "Scaling tiles to " + size);
		TileSystem.setTileSize(size);
	}

	public void setTileSource(final ITileSource aTileSource) {
		mTileProvider.setTileSource(aTileSource);
		updateTileSizeForDensity(aTileSource);
		this.checkZoomButtons();
		this.setZoomLevel(mZoomLevel); // revalidate zoom level
		postInvalidate();
	}

	/**
	 * @param aZoomLevel
	 *            the zoom level bound by the tile source
	 * Used to be an int - is a double since 6.0
	 */
	double setZoomLevel(final double aZoomLevel) {
		final int minZoomLevel = getMinZoomLevel();
		final int maxZoomLevel = getMaxZoomLevel();

		final double newZoomLevel = Math.max(minZoomLevel, Math.min(maxZoomLevel, aZoomLevel));
		final double curZoomLevel = this.mZoomLevel;

		if (newZoomLevel != curZoomLevel) {
			if (mScroller!=null)	//fix for edit mode in the IDE
				mScroller.forceFinished(true);
			mIsFlinging = false;
		}

		// Get our current center point
		final IGeoPoint centerGeoPoint = getMapCenter();

		this.mZoomLevel = newZoomLevel;

		setCenter(centerGeoPoint);
		this.checkZoomButtons();

		if (isLayoutOccurred()) {
			getController().setCenter(centerGeoPoint);

			// snap for all snappables
			final Point snapPoint = new Point();
			final Projection pj = getProjection();
			if (this.getOverlayManager().onSnapToItem((int) mMultiTouchScalePoint.x,
					(int) mMultiTouchScalePoint.y, snapPoint, this)) {
				IGeoPoint geoPoint = pj.fromPixels(snapPoint.x, snapPoint.y, null);
				getController().animateTo(geoPoint);
			}

			mTileProvider.rescaleCache(pj, newZoomLevel, curZoomLevel, getScreenRect(null));
			pauseFling = true;	// issue 269, pause fling during zoom changes
		}

		// do callback on listener
		if (newZoomLevel != curZoomLevel && mListener != null) {
			final ZoomEvent event = new ZoomEvent(this, newZoomLevel);
			mListener.onZoom(event);
		}

		// Allows any views fixed to a Location in the MapView to adjust
		this.requestLayout();
		return this.mZoomLevel;
	}

	@Deprecated
	public void zoomToBoundingBox(final BoundingBoxE6 boundingBox) {
		BoundingBox box = new BoundingBox(boundingBox.getLatNorthE6()/1e6, boundingBox.getLonEastE6()/1e6, boundingBox.getLatSouthE6()/1e6, boundingBox.getLonWestE6()/1e6);
		zoomToBoundingBox(box, false);
	}

	/**
	 * Zoom the map to enclose the specified bounding box, as closely as possible. Must be called
	 * after display layout is complete, or screen dimensions are not known, and will always zoom to
	 * center of zoom level 0.<br>
	 * Suggestion: Check getIntrinsicScreenRect(null).getHeight() &gt; 0
	 */
	public void zoomToBoundingBox(final BoundingBox boundingBox, final boolean animated) {
		zoomToBoundingBox(boundingBox, animated, 0);
	}

	/**
	 * @since 6.0.0
	 */
	public void zoomToBoundingBox(final BoundingBox boundingBox, final boolean animated, final int borderSizeInPixels) {
		double nextZoom = TileSystem.getBoundingBoxZoom(boundingBox, getWidth() - 2 * borderSizeInPixels, getHeight() - 2 * borderSizeInPixels);
		if (nextZoom == Double.MIN_VALUE) {
			return;
		}
		nextZoom = Math.min(getMaxZoomLevel(), Math.max(nextZoom, getMinZoomLevel()));
		if(animated) {
			getController().zoomTo(nextZoom);
		} else {
			getController().setZoom(nextZoom);
		}

		getController().setCenter(boundingBox.getCenterWithDateLine());
	}

	/**
	 * Get the current ZoomLevel for the map tiles.
	 *
	 * @return the current ZoomLevel between 0 (equator) and 18/19(closest), depending on the tile
	 *         source chosen.
	 */
	@Deprecated
	@Override
	public int getZoomLevel() {
		return (int) getZoomLevelDouble();
	}

	/**
	 * @since 6.0
	 */
	@Override
	public double getZoomLevelDouble() {
		return getZoomLevel(true);
	}

	/**
	 * Get the current ZoomLevel for the map tiles.
	 *
	 * @param aPending
	 *            if true and we're animating then return the zoom level that we're animating
	 *            towards, otherwise return the current zoom level
	 * Used to be an int - is a double since 6.0
	 * @return the zoom level
	 */
	public double getZoomLevel(final boolean aPending) {
		if (aPending && isAnimating()) {
			return mTargetZoomLevel.get();
		} else {
			return mZoomLevel;
		}
	}

	/**
	 * Get the minimum allowed zoom level for the maps.
	 */
	public int getMinZoomLevel() {
		return mMinimumZoomLevel == null ? mMapOverlay.getMinimumZoomLevel() : mMinimumZoomLevel;
	}

	/**
	 * Get the maximum allowed zoom level for the maps.
	 */
	@Override
	public int getMaxZoomLevel() {
		return mMaximumZoomLevel == null ? mMapOverlay.getMaximumZoomLevel() : mMaximumZoomLevel;
	}

	/**
	 * Set the minimum allowed zoom level, or pass null to use the minimum zoom level from the tile
	 * provider.
	 */
	public void setMinZoomLevel(Integer zoomLevel) {
		mMinimumZoomLevel = zoomLevel;
	}

	/**
	 * Set the maximum allowed zoom level, or pass null to use the maximum zoom level from the tile
	 * provider.
	 */
	public void setMaxZoomLevel(Integer zoomLevel) {
		mMaximumZoomLevel = zoomLevel;
	}

	public boolean canZoomIn() {
		final int maxZoomLevel = getMaxZoomLevel();
		if ((isAnimating() ? mTargetZoomLevel.get() : mZoomLevel) >= maxZoomLevel) {
			return false;
		}
		return true;
	}

	public boolean canZoomOut() {
		final int minZoomLevel = getMinZoomLevel();
		if ((isAnimating() ? mTargetZoomLevel.get() : mZoomLevel) <= minZoomLevel) {
			return false;
		}
		return true;
	}

	/**
	 * Zoom in by one zoom level.
	 */
	boolean zoomIn() {
		return getController().zoomIn();
	}

	@Deprecated
	boolean zoomInFixing(final IGeoPoint point) {
		Point coords = getProjection().toPixels(point, null);
		return getController().zoomInFixing(coords.x, coords.y);
	}

	@Deprecated
	boolean zoomInFixing(final int xPixel, final int yPixel) {
		return getController().zoomInFixing(xPixel, yPixel);
	}

	/**
	 * Zoom out by one zoom level.
	 */
	boolean zoomOut() {
		return getController().zoomOut();
	}

	@Deprecated
	boolean zoomOutFixing(final IGeoPoint point) {
		Point coords = getProjection().toPixels(point, null);
		return zoomOutFixing(coords.x, coords.y);
	}

	@Deprecated
	boolean zoomOutFixing(final int xPixel, final int yPixel) {
		return getController().zoomOutFixing(xPixel, yPixel);
	}

	/**
	 * Returns the current center-point position of the map, as a GeoPoint (latitude and longitude).
	 *
	 * @return A GeoPoint of the map's center-point.
	 */
	@Override
	public IGeoPoint getMapCenter() {
		return getProjection().fromPixels(getWidth() / 2, getHeight() / 2, null);
	}


	/**
	 * rotates the map to the desired heading
	 * @param degrees
     */
	public void setMapOrientation(float degrees) {
		setMapOrientation(degrees, true);
	}

	/**
	 * There are some cases when we don't need explicit redraw
	 * @since 6.0.0
	 */
	public void setMapOrientation(final float degrees, final boolean forceRedraw) {
		mapOrientation = degrees % 360.0f;
		if (forceRedraw) {
			// Request a layout, so that children are correctly positioned according to map orientation
			requestLayout();
			invalidate();
		}
	}

	public float getMapOrientation() {
		return mapOrientation;
	}

	/**
	 * @since 5.6.6
	 */
	public float getMapScale() {
		return mMultiTouchScale;
	}

	/**
	 * Whether to use the network connection if it's available.
	 */
	public boolean useDataConnection() {
		return mMapOverlay.useDataConnection();
	}

	/**
	 * Set whether to use the network connection if it's available.
	 *
	 * @param aMode
	 *            if true use the network connection if it's available. if false don't use the
	 *            network connection even if it's available.
	 */
	public void setUseDataConnection(final boolean aMode) {
		mMapOverlay.setUseDataConnection(aMode);
	}

	/**
	 * Use {@link #setScrollableAreaLimitDouble(BoundingBox)} instead
	 */
     @Deprecated
	public void setScrollableAreaLimit(BoundingBoxE6 boundingBox) {
		 setScrollableAreaLimitDouble(boundingBox == null ? null : new BoundingBox(
				boundingBox.getLatNorthE6()/1E6, boundingBox.getLonEastE6()/1E6,
				boundingBox.getLatSouthE6()/1E6, boundingBox.getLonWestE6()/1E6));
	}

    /**
     * Set the map to limit it's scrollable view to the specified BoundingBox. Note this does not
     * limit zooming so it will be possible for the user to zoom to an area that is larger than the
     * limited area.
     *
     * @param boundingBox
     *            A lat/long bounding box to limit scrolling to, or null to remove any scrolling
     *            limitations
     */
    public void setScrollableAreaLimitDouble(BoundingBox boundingBox) {
        mScrollableAreaBoundingBox = boundingBox;

        // Clear scrollable area limit if null passed.
        if (boundingBox == null) {
            mScrollableAreaLimit = null;
            return;
        }

        // Get NW/upper-left
        final PointL upperLeft = TileSystem.getMercatorFromGeo(
        		boundingBox.getLatNorth(), boundingBox.getLonWest(),
				microsoft.mappoint.TileSystem.getMaximumZoomLevel(), null);

        // Get SE/lower-right
        final PointL lowerRight = TileSystem.getMercatorFromGeo(
        		boundingBox.getLatSouth(), boundingBox.getLonEast(),
				microsoft.mappoint.TileSystem.getMaximumZoomLevel(), null);
        mScrollableAreaLimit = new RectL(upperLeft.x, upperLeft.y, lowerRight.x, lowerRight.y);
    }


    public BoundingBox getScrollableAreaLimit() {
        return mScrollableAreaBoundingBox;
    }


	public void invalidateMapCoordinates(Rect dirty) {
		invalidateMapCoordinates(dirty.left, dirty.top, dirty.right, dirty.bottom, false);
	}

	public void invalidateMapCoordinates(int left, int top, int right, int bottom) {
		invalidateMapCoordinates(left, top, right, bottom, false);
	}

	public void postInvalidateMapCoordinates(int left, int top, int right, int bottom) {
		invalidateMapCoordinates(left, top, right, bottom, true);
	}

	private void invalidateMapCoordinates(int left, int top, int right, int bottom, boolean post) {
		mInvalidateRect.set(left, top, right, bottom);

		final int centerX = getWidth() / 2;
		final int centerY = getHeight() / 2;

		if (this.getMapOrientation() != 0)
			GeometryMath.getBoundingBoxForRotatatedRectangle(mInvalidateRect, centerX, centerY,
					this.getMapOrientation() + 180, mInvalidateRect);

		if (post)
			super.postInvalidate(mInvalidateRect.left, mInvalidateRect.top, mInvalidateRect.right,
					mInvalidateRect.bottom);
		else
			super.invalidate(mInvalidateRect);
	}

	/**
	 * Returns a set of layout parameters with a width of
	 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}, a height of
	 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} at the {@link GeoPoint} (0, 0) align
	 * with {@link MapView.LayoutParams#BOTTOM_CENTER}.
	 */
	@Override
	protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
		return new MapView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, null, MapView.LayoutParams.BOTTOM_CENTER, 0, 0);
	}

	@Override
	public ViewGroup.LayoutParams generateLayoutParams(final AttributeSet attrs) {
		return new MapView.LayoutParams(getContext(), attrs);
	}

	// Override to allow type-checking of LayoutParams.
	@Override
	protected boolean checkLayoutParams(final ViewGroup.LayoutParams p) {
		return p instanceof MapView.LayoutParams;
	}

	@Override
	protected ViewGroup.LayoutParams generateLayoutParams(final ViewGroup.LayoutParams p) {
		return new MapView.LayoutParams(p);
	}

	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		// Get the children to measure themselves so we know their size in onLayout()
		measureChildren(widthMeasureSpec, heightMeasureSpec);

		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r,
                            final int b) {
        myOnLayout(changed, l, t, r, b);
    }

    /**
     * Code was moved from {@link #onLayout(boolean, int, int, int, int)}
     * in order to avoid Android Studio warnings on direct calls
     * @since 6.0.0
     */
	protected void myOnLayout(final boolean changed, final int l, final int t, final int r,
			final int b) {
		resetProjection();
		final int count = getChildCount();

		for (int i = 0; i < count; i++) {
			final View child = getChildAt(i);
			if (child.getVisibility() != GONE) {

				final MapView.LayoutParams lp = (MapView.LayoutParams) child.getLayoutParams();
				final int childHeight = child.getMeasuredHeight();
				final int childWidth = child.getMeasuredWidth();
				getProjection().toPixels(lp.geoPoint, mLayoutPoint);
				// Apply rotation of mLayoutPoint around the center of the map
				if (getMapOrientation() != 0) {
					Point p = getProjection().rotateAndScalePoint(mLayoutPoint.x, mLayoutPoint.y,
							null);
					mLayoutPoint.x = p.x;
					mLayoutPoint.y = p.y;
				}
				final long x = mLayoutPoint.x;
				final long y = mLayoutPoint.y;
				long childLeft = x;
				long childTop = y;
				switch (lp.alignment) {
				case MapView.LayoutParams.TOP_LEFT:
					childLeft = getPaddingLeft() + x;
					childTop = getPaddingTop() + y;
					break;
				case MapView.LayoutParams.TOP_CENTER:
					childLeft = getPaddingLeft() + x - childWidth / 2;
					childTop = getPaddingTop() + y;
					break;
				case MapView.LayoutParams.TOP_RIGHT:
					childLeft = getPaddingLeft() + x - childWidth;
					childTop = getPaddingTop() + y;
					break;
				case MapView.LayoutParams.CENTER_LEFT:
					childLeft = getPaddingLeft() + x;
					childTop = getPaddingTop() + y - childHeight / 2;
					break;
				case MapView.LayoutParams.CENTER:
					childLeft = getPaddingLeft() + x - childWidth / 2;
					childTop = getPaddingTop() + y - childHeight / 2;
					break;
				case MapView.LayoutParams.CENTER_RIGHT:
					childLeft = getPaddingLeft() + x - childWidth;
					childTop = getPaddingTop() + y - childHeight / 2;
					break;
				case MapView.LayoutParams.BOTTOM_LEFT:
					childLeft = getPaddingLeft() + x;
					childTop = getPaddingTop() + y - childHeight;
					break;
				case MapView.LayoutParams.BOTTOM_CENTER:
					childLeft = getPaddingLeft() + x - childWidth / 2;
					childTop = getPaddingTop() + y - childHeight;
					break;
				case MapView.LayoutParams.BOTTOM_RIGHT:
					childLeft = getPaddingLeft() + x - childWidth;
					childTop = getPaddingTop() + y - childHeight;
					break;
				}
				childLeft += lp.offsetX;
				childTop += lp.offsetY;
				child.layout(
						TileSystem.truncateToInt(childLeft), TileSystem.truncateToInt(childTop),
						TileSystem.truncateToInt(childLeft + childWidth), TileSystem.truncateToInt(childTop + childHeight));
			}
		}
		if (!isLayoutOccurred()) {
			mLayoutOccurred = true;
			for (OnFirstLayoutListener listener : mOnFirstLayoutListeners)
				listener.onFirstLayout(this, l, t, r, b);
			mOnFirstLayoutListeners.clear();
		}
		resetProjection();
	}

	public void addOnFirstLayoutListener(OnFirstLayoutListener listener) {
		// Don't add if we already have a layout
		if (!isLayoutOccurred())
			mOnFirstLayoutListeners.add(listener);
	}

	public void removeOnFirstLayoutListener(OnFirstLayoutListener listener) {
		mOnFirstLayoutListeners.remove(listener);
	}

	public boolean isLayoutOccurred() {
		return mLayoutOccurred;
	}

	public void onDetach() {
		this.getOverlayManager().onDetach(this);
		mTileProvider.detach();
		mTileProvider.clearTileCache();

		//https://github.com/osmdroid/osmdroid/issues/390
		if (mTileRequestCompleteHandler instanceof SimpleInvalidationHandler) {
			((SimpleInvalidationHandler) mTileRequestCompleteHandler).destroy();
		}
		mTileRequestCompleteHandler=null;
		if (mProjection!=null)
			mProjection.detach();
		mProjection=null;
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		final boolean result = this.getOverlayManager().onKeyDown(keyCode, event, this);

		return result || super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(final int keyCode, final KeyEvent event) {
		final boolean result = this.getOverlayManager().onKeyUp(keyCode, event, this);

		return result || super.onKeyUp(keyCode, event);
	}

	@Override
	public boolean onTrackballEvent(final MotionEvent event) {

		if (this.getOverlayManager().onTrackballEvent(event, this)) {
			return true;
		}

		scrollBy((int) (event.getX() * 25), (int) (event.getY() * 25));

		return super.onTrackballEvent(event);
	}

	@Override
	public boolean dispatchTouchEvent(final MotionEvent event) {

		if (Configuration.getInstance().isDebugMapView()) {
			Log.d(IMapView.LOGTAG,"dispatchTouchEvent(" + event + ")");
		}

		if (mZoomButtonsOverlay.onTouchEvent(event, this)) {
			return true;
		}

		// Get rotated event for some touch listeners.
		MotionEvent rotatedEvent = rotateTouchEvent(event);

		try {
			if (super.dispatchTouchEvent(event)) {
				if (Configuration.getInstance().isDebugMapView()) {
					Log.d(IMapView.LOGTAG,"super handled onTouchEvent");
				}
				return true;
			}

			if (this.getOverlayManager().onTouchEvent(rotatedEvent, this)) {
				return true;
			}

			boolean handled = false;
			if (mMultiTouchController != null && mMultiTouchController.onTouchEvent(event)) {
				if (Configuration.getInstance().isDebugMapView()) {
					Log.d(IMapView.LOGTAG,"mMultiTouchController handled onTouchEvent");
				}
				handled = true;
			}

			if (mGestureDetector.onTouchEvent(rotatedEvent)) {
				if (Configuration.getInstance().isDebugMapView()) {
					Log.d(IMapView.LOGTAG,"mGestureDetector handled onTouchEvent");
				}
				handled = true;
			}

			if (handled)
				return true;
		} finally {
			if (rotatedEvent != event)
				rotatedEvent.recycle();
		}

		if (Configuration.getInstance().isDebugMapView()) {
			Log.d(IMapView.LOGTAG,"no-one handled onTouchEvent");
		}
		return false;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return false;
	}

	private MotionEvent rotateTouchEvent(MotionEvent ev) {
		if (this.getMapOrientation() == 0)
			return ev;

		MotionEvent rotatedEvent = MotionEvent.obtain(ev);
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			getProjection().unrotateAndScalePoint((int) ev.getX(), (int) ev.getY(),
					mRotateScalePoint);
			rotatedEvent.setLocation(mRotateScalePoint.x, mRotateScalePoint.y);
		} else {
			// This method is preferred since it will rotate historical touch events too
			try {
				if (sMotionEventTransformMethod == null) {
					sMotionEventTransformMethod = MotionEvent.class.getDeclaredMethod("transform",
							new Class[] { Matrix.class });
				}
				sMotionEventTransformMethod.invoke(rotatedEvent, getProjection()
						.getInvertedScaleRotateCanvasMatrix());
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
		return rotatedEvent;
	}

	@Override
	public void computeScroll() {
		if (mScroller!=null)	//fix for edit mode in the IDE
			if (mScroller.computeScrollOffset()) {
				if (mScroller.isFinished()) {
					// One last scrollTo to get to the final destination
					scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
					mIsFlinging = false;
				} else {
					scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
				}
				// Keep on drawing until the animation has finished.
				postInvalidate();
			}
	}

	@Override
	public void scrollTo(int x, int y) {
		if (mScrollableAreaLimit != null) {
			final double currentZoomLevel = this.getZoomLevel(false);
			final double zoomDiff = microsoft.mappoint.TileSystem.getMaximumZoomLevel()
					- currentZoomLevel;
			final double power = TileSystem.getFactor(zoomDiff);
			final int minX = (int)(mScrollableAreaLimit.left / power);
			final int minY = (int)(mScrollableAreaLimit.top / power);
			final int maxX = (int)(mScrollableAreaLimit.right / power);
			final int maxY = (int)(mScrollableAreaLimit.bottom / power);

			final int scrollableWidth = maxX - minX;
			final int scrollableHeight = maxY - minY;
			final int width = this.getWidth();
			final int height = this.getHeight();

			// Adjust if we are outside the scrollable area
			if (scrollableWidth <= width) {
				if (x > minX)
					x = minX;
				else if (x + width < maxX)
					x = maxX - width;
			} else if (x < minX)
				x = minX;
			else if (x + width > maxX)
				x = maxX - width;

			if (scrollableHeight <= height) {
				if (y > minY)
					y = minY;
				else if (y + height < maxY)
					y = maxY - height;
			} else if (y - (0) < minY)
				y = minY + (0);
			else if (y + (height) > maxY)
				y = maxY - (height);
		}
		setMapScroll(x, y);
		resetProjection();
		invalidate();

		// Force a layout, so that children are correctly positioned according to map orientation
		if (getMapOrientation() != 0f)
			myOnLayout(true, getLeft(), getTop(), getRight(), getBottom());

		// do callback on listener
		if (mListener != null) {
			final ScrollEvent event = new ScrollEvent(this, x, y);
			mListener.onScroll(event);
		}
	}

	/**
	 * @since 5.6.6
	 */
	@Override
	public void scrollBy(int x, int y) {
		scrollTo((int)(getMapScrollX() + x), (int)(getMapScrollY() + y));
	}

	@Override
	public void setBackgroundColor(final int pColor) {
		mMapOverlay.setLoadingBackgroundColor(pColor);
		invalidate();
	}

	@Override
	protected void dispatchDraw(final Canvas c) {
		final long startMs = System.currentTimeMillis();

		// Reset the projection
		resetProjection();

		// Save the current canvas matrix
		c.save();

		// Apply the scale and rotate operations
		c.concat(getProjection().getScaleRotateCanvasMatrix());

		/* Draw background */
		// c.drawColor(mBackgroundColor);
		try {
			/* Draw all Overlays. */
			this.getOverlayManager().onDraw(c, this);
			mZoomButtonsOverlay.draw(c, this, false);
			// Restore the canvas matrix
			c.restore();
			super.dispatchDraw(c);
		}catch (Exception ex){
			//for edit mode
			Log.e(IMapView.LOGTAG, "error dispatchDraw, probably in edit mode", ex);
		}
		if (Configuration.getInstance().isDebugMapView()) {
			final long endMs = System.currentTimeMillis();
			Log.d(IMapView.LOGTAG,"Rendering overall: " + (endMs - startMs) + "ms");
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		this.onDetach();
		super.onDetachedFromWindow();
	}

	// ===========================================================
	// Animation
	// ===========================================================

	/**
	 * Determines if maps are animating a zoom operation. Useful for overlays to avoid recalculating
	 * during an animation sequence.
	 *
	 * @return boolean indicating whether view is animating.
	 */
	public boolean isAnimating() {
		return mIsAnimating.get();
	}

	// ===========================================================
	// Implementation of MultiTouchObjectCanvas
	// ===========================================================

	@Override
	public Object getDraggableObjectAtPoint(final PointInfo pt) {
		if (this.isAnimating()) {
			// Zoom animations use the mMultiTouchScale variables to perform their animations so we
			// don't want to step on that.
			return null;
		} else {
			mMultiTouchScalePoint.x = pt.getX();
			mMultiTouchScalePoint.y = pt.getY();
			return this;
		}
	}

	@Override
	public void getPositionAndScale(final Object obj, final PositionAndScale objPosAndScaleOut) {
		objPosAndScaleOut.set(0, 0, true, mMultiTouchScale, false, 0, 0, false, 0);
	}

	@Override
	public void selectObject(final Object obj, final PointInfo pt) {
		// if obj is null it means we released the pointers
		// if scale is not 1 it means we pinched
		if (obj == null && mMultiTouchScale != 1.0f) {
			final float scaleDiffFloat = (float) (Math.log(mMultiTouchScale) * ZOOM_LOG_BASE_INV);
			// If we are changing zoom levels,
			// adjust the center point in respect to the scaling point
			if (scaleDiffFloat != 0) {
				final Rect screenRect = getProjection().getScreenRect();
				getProjection().unrotateAndScalePoint(screenRect.centerX(), screenRect.centerY(),
						mRotateScalePoint);
				setCenter(getProjection().fromPixels(mRotateScalePoint.x, mRotateScalePoint.y));
			}

			// Adjust the zoomLevel
			setZoomLevel(mZoomLevel + scaleDiffFloat);
		}

		// reset scale
		mMultiTouchScale = 1.0f;
	}

	@Override
	public boolean setPositionAndScale(final Object obj, final PositionAndScale aNewObjPosAndScale,
			final PointInfo aTouchPoint) {
		float multiTouchScale = aNewObjPosAndScale.getScale();
		// If we are at the first or last zoom level, prevent pinching/expanding
		if (multiTouchScale > 1 && !canZoomIn()) {
			multiTouchScale = 1;
		}
		if (multiTouchScale < 1 && !canZoomOut()) {
			multiTouchScale = 1;
		}
		mMultiTouchScale = multiTouchScale;
		// Request a layout, so that children are correctly positioned according to scale
		requestLayout();
		invalidate(); // redraw
		return true;
	}

	/*
	 * Set the MapListener for this view
	 */
	public void setMapListener(final MapListener ml) {
		mListener = ml;
	}

	// ===========================================================
	// Methods
	// ===========================================================

	private void checkZoomButtons() {
		mZoomButtonsOverlay.setZoomInEnabled(canZoomIn());
		mZoomButtonsOverlay.setZoomOutEnabled(canZoomOut());
	}

	public void setBuiltInZoomControls(final boolean on) {
		setBuiltInZoomControls(on,
				ZoomButtonsOverlay.POSITION_HORIZONTAL_DEFAULT
				| ZoomButtonsOverlay.POSITION_VERTICAL_DEFAULT);
	}

	/**
	 * @since 6.0.0
	 * @param pPosition OR'ed value between {@link ZoomButtonsOverlay#POSITION_HORIZONTAL_POSSIBLE}
	 *                     and {@link ZoomButtonsOverlay#POSITION_VERTICAL_POSSIBLE}
	 */
	public void setBuiltInZoomControls(final boolean on, final int pPosition) {
		mZoomButtonsOverlay.setEnabled(on);
		mZoomButtonsOverlay.setPosition(pPosition);
		checkZoomButtons();
	}

	/**
	 * @since 6.0.0
	 * @param pLeft Explicit left pixel of the first zoom button
	 * @param pTop Explicit top pixel of both zoom buttons
	 */
	public void setBuiltInZoomControls(final boolean on, final int pLeft, final int pTop) {
		mZoomButtonsOverlay.setEnabled(on);
		mZoomButtonsOverlay.setLeftTop(pLeft, pTop);
		checkZoomButtons();
	}

	public void setMultiTouchControls(final boolean on) {
		mMultiTouchController = on ? new MultiTouchController<Object>(this, false) : null;
	}

	private ITileSource getTileSourceFromAttributes(final AttributeSet aAttributeSet) {

		ITileSource tileSource = TileSourceFactory.DEFAULT_TILE_SOURCE;

		if (aAttributeSet != null) {
			final String tileSourceAttr = aAttributeSet.getAttributeValue(null, "tilesource");
			if (tileSourceAttr != null) {
				try {
					final ITileSource r = TileSourceFactory.getTileSource(tileSourceAttr);
					Log.i(IMapView.LOGTAG,"Using tile source specified in layout attributes: " + r);
					tileSource = r;
				} catch (final IllegalArgumentException e) {
					Log.w(IMapView.LOGTAG,"Invalid tile source specified in layout attributes: " + tileSource);
				}
			}
		}

		if (aAttributeSet != null && tileSource instanceof IStyledTileSource) {
			final String style = aAttributeSet.getAttributeValue(null, "style");
			if (style == null) {
				Log.i(IMapView.LOGTAG,"Using default style: 1");
			} else {
				Log.i(IMapView.LOGTAG,"Using style specified in layout attributes: " + style);
				((IStyledTileSource<?>) tileSource).setStyle(style);
			}
		}

		Log.i(IMapView.LOGTAG,"Using tile source: " + tileSource.name());
		return tileSource;
	}


	private boolean enableFling = true;
	private boolean pauseFling = false;	// issue 269, boolean used for disabling fling during zoom changes
	public void setFlingEnabled(final boolean b){
		enableFling = b;
	}
	public boolean isFlingEnabled(){
		return enableFling;
	}
	// ===========================================================
	// Inner and Anonymous Classes
	// ===========================================================

	private class MapViewGestureDetectorListener implements OnGestureListener {

		@Override
		public boolean onDown(final MotionEvent e) {

			// Stop scrolling if we are in the middle of a fling!
			if (mIsFlinging) {
				if (mScroller!=null)	//fix for edit mode in the IDE
					mScroller.abortAnimation();
				mIsFlinging = false;
			}

			if (MapView.this.getOverlayManager().onDown(e, MapView.this)) {
				return true;
			}

			return true;
		}

		@Override
		public boolean onFling(final MotionEvent e1, final MotionEvent e2,
					final float velocityX, final float velocityY) {
	            	if (!enableFling || pauseFling) {
	                	// issue 269, if fling occurs during zoom changes, pauseFling is equals to true, so fling is canceled. But need to reactivate fling for next time.
	                	pauseFling = false;
	                	return false;
	            	}

			if (MapView.this.getOverlayManager()
					.onFling(e1, e2, velocityX, velocityY, MapView.this)) {
				return true;
			}

			final double worldSize = TileSystem.MapSize(MapView.this.getZoomLevel(false));
			mIsFlinging = true;
			if (mScroller!=null)	//fix for edit mode in the IDE
				mScroller.fling((int)getMapScrollX(), (int)getMapScrollY(), (int) -velocityX, (int) -velocityY,
						-(int)worldSize, (int)worldSize, -(int)worldSize, (int)worldSize);
			return true;
		}

		@Override
		public void onLongPress(final MotionEvent e) {
			if (mMultiTouchController != null && mMultiTouchController.isPinching()) {
				return;
			}
			MapView.this.getOverlayManager().onLongPress(e, MapView.this);
		}

		@Override
		public boolean onScroll(final MotionEvent e1, final MotionEvent e2, final float distanceX,
				final float distanceY) {
			if (MapView.this.getOverlayManager().onScroll(e1, e2, distanceX, distanceY,
					MapView.this)) {
				return true;
			}

			scrollBy((int) distanceX, (int) distanceY);
			return true;
		}

		@Override
		public void onShowPress(final MotionEvent e) {
			MapView.this.getOverlayManager().onShowPress(e, MapView.this);
		}

		@Override
		public boolean onSingleTapUp(final MotionEvent e) {
			if (MapView.this.getOverlayManager().onSingleTapUp(e, MapView.this)) {
				return true;
			}

			return false;
		}

	}

	private class MapViewDoubleClickListener implements GestureDetector.OnDoubleTapListener {
		@Override
		public boolean onDoubleTap(final MotionEvent e) {
			if (MapView.this.getOverlayManager().onDoubleTap(e, MapView.this)) {
				return true;
			}

			// final IGeoPoint center = getProjection().fromPixels((int) e.getX(), (int) e.getY(),
			// null);
			getProjection().rotateAndScalePoint((int) e.getX(), (int) e.getY(), mRotateScalePoint);
			return zoomInFixing(mRotateScalePoint.x, mRotateScalePoint.y);
		}

		@Override
		public boolean onDoubleTapEvent(final MotionEvent e) {
			if (MapView.this.getOverlayManager().onDoubleTapEvent(e, MapView.this)) {
				return true;
			}

			return false;
		}

		@Override
		public boolean onSingleTapConfirmed(final MotionEvent e) {
			if (MapView.this.getOverlayManager().onSingleTapConfirmed(e, MapView.this)) {
				return true;
			}

			return false;
		}
	}

	// ===========================================================
	// Public Classes
	// ===========================================================

	/**
	 * Per-child layout information associated with OpenStreetMapView.
	 */
	public static class LayoutParams extends ViewGroup.LayoutParams {

		/**
		 * Special value for the alignment requested by a View. TOP_LEFT means that the location
		 * will at the top left the View.
		 */
		public static final int TOP_LEFT = 1;
		/**
		 * Special value for the alignment requested by a View. TOP_RIGHT means that the location
		 * will be centered at the top of the View.
		 */
		public static final int TOP_CENTER = 2;
		/**
		 * Special value for the alignment requested by a View. TOP_RIGHT means that the location
		 * will at the top right the View.
		 */
		public static final int TOP_RIGHT = 3;
		/**
		 * Special value for the alignment requested by a View. CENTER_LEFT means that the location
		 * will at the center left the View.
		 */
		public static final int CENTER_LEFT = 4;
		/**
		 * Special value for the alignment requested by a View. CENTER means that the location will
		 * be centered at the center of the View.
		 */
		public static final int CENTER = 5;
		/**
		 * Special value for the alignment requested by a View. CENTER_RIGHT means that the location
		 * will at the center right the View.
		 */
		public static final int CENTER_RIGHT = 6;
		/**
		 * Special value for the alignment requested by a View. BOTTOM_LEFT means that the location
		 * will be at the bottom left of the View.
		 */
		public static final int BOTTOM_LEFT = 7;
		/**
		 * Special value for the alignment requested by a View. BOTTOM_CENTER means that the
		 * location will be centered at the bottom of the view.
		 */
		public static final int BOTTOM_CENTER = 8;
		/**
		 * Special value for the alignment requested by a View. BOTTOM_RIGHT means that the location
		 * will be at the bottom right of the View.
		 */
		public static final int BOTTOM_RIGHT = 9;
		/**
		 * The location of the child within the map view.
		 */
		public IGeoPoint geoPoint;

		/**
		 * The alignment the alignment of the view compared to the location.
		 */
		public int alignment;

		public int offsetX;
		public int offsetY;

		/**
		 * Creates a new set of layout parameters with the specified width, height and location.
		 *
		 * @param width
		 *            the width, either {@link #FILL_PARENT}, {@link #WRAP_CONTENT} or a fixed size
		 *            in pixels
		 * @param height
		 *            the height, either {@link #FILL_PARENT}, {@link #WRAP_CONTENT} or a fixed size
		 *            in pixels
		 * @param geoPoint
		 *            the location of the child within the map view
		 * @param alignment
		 *            the alignment of the view compared to the location {@link #BOTTOM_CENTER},
		 *            {@link #BOTTOM_LEFT}, {@link #BOTTOM_RIGHT} {@link #TOP_CENTER},
		 *            {@link #TOP_LEFT}, {@link #TOP_RIGHT}
		 * @param offsetX
		 *            the additional X offset from the alignment location to draw the child within
		 *            the map view
		 * @param offsetY
		 *            the additional Y offset from the alignment location to draw the child within
		 *            the map view
		 */
		public LayoutParams(final int width, final int height, final IGeoPoint geoPoint,
				final int alignment, final int offsetX, final int offsetY) {
			super(width, height);
			if (geoPoint != null) {
				this.geoPoint = geoPoint;
			} else {
				this.geoPoint = new GeoPoint(0, 0);
			}
			this.alignment = alignment;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
		}

		/**
		 * Since we cannot use XML files in this project this constructor is useless. Creates a new
		 * set of layout parameters. The values are extracted from the supplied attributes set and
		 * context.
		 *
		 * @param c
		 *            the application environment
		 * @param attrs
		 *            the set of attributes fom which to extract the layout parameters values
		 */
		public LayoutParams(final Context c, final AttributeSet attrs) {
			super(c, attrs);
			this.geoPoint = new GeoPoint(0, 0);
			this.alignment = BOTTOM_CENTER;
		}

		public LayoutParams(final ViewGroup.LayoutParams source) {
			super(source);
		}
	}
	
	
	/**
	 * enables you to programmatically set the tile provider (zip, assets, sqlite, etc)
	 * @since 4.4
	 * @param base 
	 * @see MapTileProviderBasic
	 */
	public void setTileProvider(final  MapTileProviderBase base){
		this.mTileProvider.detach();
		mTileProvider.clearTileCache();
		this.mTileProvider=base;
		mTileProvider.setTileRequestCompleteHandler(mTileRequestCompleteHandler);
		updateTileSizeForDensity(mTileProvider.getTileSource());

		this.mMapOverlay = new TilesOverlay(mTileProvider, this.getContext());
		
		mOverlayManager.setTilesOverlay(mMapOverlay);
		invalidate();
	}

	/**
	 * @since 6.0.0
	 */
	@Deprecated
	public void setInitCenter(final IGeoPoint geoPoint) {
		setCenter(geoPoint);
	}

	public long getMapScrollX() {
		return mMapScrollX;
	}

	public long getMapScrollY() {
		return mMapScrollY;
	}

	private void setMapScroll(final long pMapScrollX, final long pMapScrollY) {
		mMapScrollX = pMapScrollX;
		mMapScrollY = pMapScrollY;
		requestLayout();
	}

	/**
	 * @since 5.6.6
	 */
	public GeoPoint getCenter() {
		return mCenter;
	}

	/**
	 * @since 5.6.6
	 */
	public void setCenter(final IGeoPoint pGeoPoint) {
		mCenter = (GeoPoint)pGeoPoint;
		setMapScroll(0, 0);
		resetProjection();
	}
}
