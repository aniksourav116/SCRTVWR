package srv.scrtvwr.ui.media

enum class SlideShowDirection {
    Next,
    Previous,
}

val SlideShowDirection.pageOffset: Int
    get() = when (this) {
        SlideShowDirection.Next -> 1
        SlideShowDirection.Previous -> -1
    }
