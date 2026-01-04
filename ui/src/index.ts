import { definePlugin } from '@halo-dev/ui-shared'
import { IconFolder, VDropdownItem } from '@halo-dev/components'
import { markRaw, type Ref } from 'vue'
import type { Attachment } from '@halo-dev/api-client'
import { useRouter } from 'vue-router'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/storage-toolkit',
        name: 'StorageToolkit',
        component: () => import('./views/StorageToolkitView.vue'),
        meta: {
          menu: {
            name: '存储工具箱',
            group: 'tool',
            icon: markRaw(IconFolder),
          },
        },
      },
    },
  ],
  extensionPoints: {
    'attachment:list-item:operation:create': (attachment: Ref<Attachment>) => {
      const router = useRouter()
      return [
        {
          priority: 20,
          component: markRaw(VDropdownItem),
          props: {},
          action: () => {
            const attachmentName = attachment.value?.metadata?.name
            if (attachmentName) {
              router.push({
                name: 'StorageToolkit',
                query: { tab: 'analysis', attachment: attachmentName }
              })
            }
          },
          label: '查看引用',
          hidden: false,
          permissions: [],
          children: [],
        },
      ]
    },
  },
})
