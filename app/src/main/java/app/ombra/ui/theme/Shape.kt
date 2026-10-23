package app.ombra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Soft, generous corners; cards use [Shapes.large]. */
val OmbraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    largeIncreased = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
    extraLargeIncreased = RoundedCornerShape(34.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
