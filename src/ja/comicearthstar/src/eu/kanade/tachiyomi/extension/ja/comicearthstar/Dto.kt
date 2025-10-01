package eu.kanade.tachiyomi.extension.ja.comicearthstar

import kotlinx.serialization.Serializable

@Serializable
class GraphQLResponse<T>(val data: T)

@Serializable
class Payload<T>(
    val operationName: String,
    val variables: T,
    val query: String,
)

@Serializable
object EmptyVariables

@Serializable
class OneshotDto(
    val seriesOneshot: SerialGroup?,
)

@Serializable
class SerialGroup(
    val seriesSlice: SeriesSlice,
)

@Serializable
class SeriesSlice(
    val seriesList: List<SeriesItem>?,
)

@Serializable
class SeriesItem(
    val title: String,
    val thumbnailUri: String,
    val firstEpisode: FirstEpisode,
)

@Serializable
class FirstEpisode(
    val permalink: String,
)
