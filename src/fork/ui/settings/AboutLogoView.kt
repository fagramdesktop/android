package xie.fa.gram.ui.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator

class AboutLogoView(context: Context) : View(context) {

    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scallopStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val planeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val planeMainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val matrixOuter = Matrix()
    private val matrixInner = Matrix()

    private val materialShapePath = Path()
    private val scallopPath = Path()
    private val planeShadowPath = Path()
    private val planeMainPath = Path()

    var onLogoTouchDown: ((x: Float, y: Float) -> Unit)? = null

    private var touchScale = 1.0f
    private var scaleAnimator: ValueAnimator? = null

    init {
        isClickable = true
    }

    fun animateScale(target: Float) {
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(touchScale, target).apply {
            duration = if (target < 1f) 120L else 260L
            interpolator = if (target < 1f) CubicBezierInterpolator.EASE_OUT else OvershootInterpolator(2.0f)
            addUpdateListener {
                touchScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animateScale(0.93f)
                onLogoTouchDown?.invoke(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animateScale(1.0f)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(180f)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val outerSize = dp(160f).toFloat()

        // material_shape2.svg: viewBox 0 0 280 280, center (140, 140)
        matrixOuter.reset()
        matrixOuter.setTranslate(-140f, -140f)
        matrixOuter.postScale(outerSize / 280f, outerSize / 280f)
        matrixOuter.postTranslate(cx, cy)
        materialShapePath.reset()
        baseMaterialShapePath.transform(matrixOuter, materialShapePath)

        // fagram.svg: viewBox 0 0 200 200, center (100, 100)
        // Scallop diameter is ~186 units; scaled to ~114dp so it sits inside the 160dp outer shape
        val scallopDiameter = dp(114f).toFloat()
        val innerScale = scallopDiameter / 186f
        matrixInner.reset()
        matrixInner.setTranslate(-100f, -100f)
        matrixInner.postScale(innerScale, innerScale)
        matrixInner.postTranslate(cx, cy)

        scallopPath.reset()
        baseScallopPath.transform(matrixInner, scallopPath)

        planeShadowPath.reset()
        basePlaneShadowPath.transform(matrixInner, planeShadowPath)

        planeMainPath.reset()
        basePlaneMainPath.transform(matrixInner, planeMainPath)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader)
        val isDark = Theme.isCurrentThemeDark()
        val cardBg = Theme.getColor(Theme.key_windowBackgroundWhite)

        val containerColor = if (isDark) {
            ColorUtils.blendARGB(cardBg, accentColor, 0.08f)
        } else {
            ColorUtils.blendARGB(cardBg, accentColor, 0.10f)
        }
        val shadowColor = if (isDark) {
            ColorUtils.blendARGB(accentColor, Color.BLACK, 0.22f)
        } else {
            ColorUtils.blendARGB(accentColor, Color.BLACK, 0.18f)
        }

        containerPaint.color = containerColor
        scallopStrokePaint.color = accentColor
        scallopStrokePaint.strokeWidth = dp(2.8f).toFloat()
        planeShadowPaint.color = shadowColor
        planeMainPaint.color = accentColor

        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        if (touchScale != 1.0f) {
            canvas.scale(touchScale, touchScale, cx, cy)
        }

        // 1. Outer 4-lobed Material shape container
        canvas.drawPath(materialShapePath, containerPaint)

        // 2. Scalloped badge stroke
        canvas.drawPath(scallopPath, scallopStrokePaint)

        // 3. Center FAgram paper-plane shadow
        canvas.drawPath(planeShadowPath, planeShadowPaint)

        // 4. Center FAgram paper-plane main body
        canvas.drawPath(planeMainPath, planeMainPaint)

        canvas.restore()
    }

    companion object {
        private const val MATERIAL_SHAPE_PATH_DATA =
            "M178.73 6.2068C238.87 -19.9132 299.91 41.1269 273.79 101.267L269.47 111.207C261.5 129.577 261.5 150.417 269.47 168.787L273.79 178.727C299.91 238.867 238.87 299.907 178.73 273.787L168.79 269.467C150.42 261.497 129.58 261.497 111.21 269.467L101.27 273.787C41.1281 299.907 -19.9139 238.867 6.20706 178.727L10.5261 168.787C18.5011 150.417 18.5011 129.577 10.5261 111.207L6.20706 101.267C-19.9139 41.1269 41.1281 -19.9132 101.27 6.2068L111.21 10.5269C129.58 18.4969 150.42 18.4969 168.79 10.5269L178.73 6.2068Z"

        private const val SCALLOP_PATH_DATA =
            "M87.4678 6.96191C94.5128 0.345179 105.483 0.345647 112.528 6.96191V6.96289C112.66 7.08891 112.804 7.23222 113.16 7.58203L113.176 7.59766L113.192 7.6123C113.376 7.78516 113.459 7.86833 113.547 7.95605L113.58 7.98926C119.079 13.2345 126.859 15.3151 134.239 13.5234L134.238 13.5225C134.375 13.4898 134.583 13.4361 134.796 13.3828L134.806 13.3799L134.815 13.3779C135.271 13.2595 135.424 13.2211 135.65 13.1631L135.651 13.1641C145.06 10.9456 154.575 16.4302 157.365 25.6865V25.6875C157.423 25.886 157.472 26.0627 157.606 26.5303V26.5312C157.68 26.8015 157.721 26.9523 157.763 27.0977L157.767 27.1113C159.902 34.3961 165.601 40.095 172.886 42.2305L172.899 42.2344L173.466 42.3906C173.938 42.5263 174.109 42.5725 174.31 42.6309C183.567 45.4205 189.052 54.9369 186.833 64.3457C186.774 64.5771 186.739 64.7221 186.619 65.1826L186.617 65.1914L186.614 65.2012C186.562 65.4105 186.506 65.6226 186.474 65.7578C184.682 73.1376 186.763 80.9168 192.008 86.416L192.024 86.4336L192.041 86.4502C192.123 86.5324 192.208 86.6171 192.384 86.8047L192.399 86.8213L192.414 86.8369C192.753 87.182 192.899 87.3266 193.022 87.4551V87.4561C199.654 94.5048 199.654 105.491 193.022 112.54C192.899 112.669 192.753 112.815 192.414 113.16L192.399 113.176L192.384 113.191C192.207 113.379 192.123 113.465 192.041 113.547L192.008 113.58C186.765 119.077 184.684 126.852 186.472 134.229V134.23C186.504 134.367 186.56 134.579 186.614 134.796L186.617 134.805L186.619 134.814C186.738 135.271 186.775 135.422 186.833 135.649V135.65C189.052 145.059 183.566 154.576 174.31 157.365C174.096 157.427 173.944 157.468 173.438 157.613C173.183 157.683 173.04 157.723 172.899 157.763L172.886 157.767C165.601 159.902 159.902 165.601 157.767 172.886L157.764 172.898C157.724 173.039 157.683 173.185 157.614 173.438C157.474 173.926 157.424 174.107 157.365 174.31C154.577 183.559 145.075 189.043 135.673 186.838C135.432 186.776 135.287 186.742 134.815 186.619L134.806 186.616L134.796 186.614L134.238 186.474H134.239C126.859 184.682 119.079 186.762 113.58 192.007L113.547 192.04C113.459 192.128 113.376 192.211 113.192 192.384L113.176 192.399L113.16 192.414C112.804 192.763 112.661 192.907 112.529 193.033C105.59 199.551 94.844 199.65 87.79 193.329L87.457 193.022C87.3284 192.899 87.1795 192.75 86.8369 192.414L86.8213 192.399L86.8047 192.384C86.6209 192.211 86.5381 192.128 86.4502 192.04L86.417 192.007L85.8945 191.524C80.6078 186.793 73.3907 184.863 66.4512 186.316L65.7578 186.474C65.6214 186.506 65.4138 186.561 65.2012 186.614L65.1914 186.616L65.1826 186.619C64.7072 186.743 64.5685 186.774 64.3232 186.837C55.0631 189.009 45.7047 183.722 42.7617 174.724L42.627 174.292C42.5705 174.097 42.5188 173.911 42.3828 173.438C42.3137 173.185 42.2745 173.039 42.2344 172.898L42.2305 172.886L42.0195 172.207C39.737 165.248 34.1684 159.835 27.1113 157.767L27.0977 157.763L26.5566 157.613H26.5576C26.056 157.469 25.897 157.426 25.6865 157.365C16.4303 154.575 10.9456 145.059 13.1641 135.65L13.1631 135.649C13.2211 135.423 13.2595 135.27 13.3779 134.814L13.3799 134.805L13.3828 134.796C13.4372 134.578 13.492 134.366 13.5244 134.229C15.3123 126.852 13.2324 119.077 7.98926 113.58L7.95605 113.547L7.6123 113.191L7.59766 113.176L7.58203 113.16L6.96289 112.528C0.345809 105.483 0.345756 94.513 6.96289 87.4678C7.08882 87.3361 7.23256 87.193 7.58203 86.8369L7.59766 86.8213L7.6123 86.8047C7.78659 86.6192 7.87105 86.5352 7.95605 86.4502L7.97266 86.4336L7.98926 86.416C13.0707 81.0885 15.1818 73.6212 13.6797 66.4502L13.5234 65.7578C13.491 65.6225 13.4352 65.4106 13.3828 65.2012L13.3799 65.1914L13.3779 65.1826L13.1641 64.3457C10.9456 54.9372 16.4301 45.4209 25.6865 42.6309L25.6875 42.6318C25.8865 42.5741 26.0616 42.5244 26.5303 42.3896L26.5312 42.3906C26.8012 42.317 26.9523 42.2759 27.0977 42.2344L27.1113 42.2305C34.3962 40.0951 40.095 34.3962 42.2305 27.1113L42.2344 27.0977C42.2759 26.9522 42.3159 26.8007 42.3896 26.5303C42.5198 26.0775 42.5716 25.8961 42.627 25.7051C45.41 16.4377 54.9312 10.9442 64.3457 13.1641V13.1631C64.576 13.2222 64.7232 13.2584 65.1826 13.3779L65.1914 13.3799L65.2012 13.3828C65.4138 13.436 65.6214 13.4897 65.7578 13.5225V13.5234C73.1377 15.3152 80.9177 13.2346 86.417 7.98926L86.4502 7.95605C86.5379 7.86836 86.6206 7.78525 86.8047 7.6123L86.8213 7.59766L86.8369 7.58203C87.1899 7.23557 87.3364 7.0876 87.4678 6.96191Z"

        private const val PLANE_SHADOW_PATH_DATA =
            "M132.482 60.9129C138.354 59.4221 143.346 65.3975 140.865 70.9468L104.756 151.701C101.755 158.411 91.8701 156.922 90.9609 149.624L85.6895 107.311C85.305 104.225 83.0098 101.726 79.9782 101.092L52.7674 95.4034C45.2427 93.8304 45.0167 83.1194 52.4683 81.2275L132.482 60.9129Z"

        private const val PLANE_MAIN_PATH_DATA =
            "M137.965 56.2318C143.837 54.741 148.829 60.7165 146.348 66.2658L110.239 147.02C107.239 153.73 97.3535 152.241 96.4443 144.943L91.1728 102.63C90.7884 99.5439 88.4932 97.0445 85.4616 96.4108L58.2508 90.7224C50.7261 89.1493 50.5001 78.4383 57.9517 76.5465L137.965 56.2318Z"

        private val baseMaterialShapePath: Path by lazy {
            PathParser.createPathFromPathData(MATERIAL_SHAPE_PATH_DATA)
        }
        private val baseScallopPath: Path by lazy {
            PathParser.createPathFromPathData(SCALLOP_PATH_DATA)
        }
        private val basePlaneShadowPath: Path by lazy {
            PathParser.createPathFromPathData(PLANE_SHADOW_PATH_DATA)
        }
        private val basePlaneMainPath: Path by lazy {
            PathParser.createPathFromPathData(PLANE_MAIN_PATH_DATA)
        }
    }
}
