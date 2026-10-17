package io.xr.lab.impl

import io.xr.lab.shared.models.MenuNode
import java.time.Instant

private const val CACHE_TTL_SECONDS = 30L * 60
private const val DEFAULT_GROUP_ICON = "resource"
private const val DEFAULT_PAGE_ICON = "file"
private const val MENU_TYPE_GROUP = "group"

/**
 * saas 菜单节点 → 排序稳定的出边列表：sortOrder null 殿后，余者升序 （fastapi menu_cache.sort key 镜像：sortOrder
 * nullsLast）。
 */
private fun sortedBySortOrder(nodes: List<SaasMenuNode>): List<SaasMenuNode> =
    nodes.sortedWith(compareBy<SaasMenuNode> { it.sortOrder == null }.thenBy { it.sortOrder ?: 0 })

/**
 * saas 菜单树 → lab 契约 MenuNode 递归映射（SaasMenuMapper 镜像）： label=title??code??id（saas 名称字段是
 * title，2026-09-14 实测）；icon 缺省按 type 兜。
 */
fun mapSaasMenus(roots: List<SaasMenuNode>): List<MenuNode> = roots.map(::mapNode)

private fun mapNode(src: SaasMenuNode): MenuNode {
  val icon =
      src.icon?.takeIf { it.isNotEmpty() }
          ?: if (src.type == MENU_TYPE_GROUP) DEFAULT_GROUP_ICON else DEFAULT_PAGE_ICON
  val children = sortedBySortOrder(src.children).map(::mapNode)
  return MenuNode(
      id = src.id,
      label =
          src.title?.takeIf { it.isNotEmpty() } ?: src.code?.takeIf { it.isNotEmpty() } ?: src.id,
      path = src.path,
      icon = icon,
      children = children.ifEmpty { null },
  )
}

/**
 * per-user 快照缓存（TTL 30min，miss 清除）。菜单/成员资格两个实例化用法； miss → Phase 5 menus 503 / me 401——本切片只写不读。
 * ConcurrentHashMap：ktor Netty 多 worker 并发读写同一用户（双标签页 refresh+SSO 交错），HashMap 有丢写窗口（终审 I1）。
 */
class SnapshotCache<T> private constructor(private val ttlSeconds: Long) {

  private val snapshots = java.util.concurrent.ConcurrentHashMap<String, Pair<T, Instant>>()

  fun put(userId: String, value: T) {
    snapshots[userId] = value to Instant.now().plusSeconds(ttlSeconds)
  }

  fun get(userId: String?): T? {
    val (value, expiresAt) = userId?.let { snapshots[it] } ?: return null
    return if (expiresAt.isBefore(Instant.now())) {
      snapshots.remove(userId)
      null
    } else {
      value
    }
  }

  companion object {

    fun <T> create(): SnapshotCache<T> = SnapshotCache(CACHE_TTL_SECONDS)
  }
}
