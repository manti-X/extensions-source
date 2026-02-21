package eu.kanade.tachiyomi.multisrc.gigaviewer

open class Queries(
    val operationPrefix: String,
) {
    open fun latestQuery(groupName: String): String = $$"""
    query Earthstar_LatestUpdates(
        $latestUpdatedSince: DateTime!,
        $latestUpdatedUntil: DateTime!
    ) {
        serialGroup(groupName: "$$groupName") {
            latestUpdatedSeriesEpisodes: updatedFreeEpisodes(
                since: $latestUpdatedSince
                until: $latestUpdatedUntil
            ) {
                permalink
                series {
                    title
                    thumbnailUri
                }
            }
        }
    }
    """.trimIndent()
/*
    open val searchQuery: String
        get() = """
            query ${operationPrefix}_Search(
                \$keyword: String!
            ) {
                searchSeries(keyword: \$keyword) {
                    edges {
                        node {
                            title
                            thumbnailUri
                            firstEpisode {
                                permalink
                            }
                        }
                    }
                }
            }
        """.trimIndent()*/
}
