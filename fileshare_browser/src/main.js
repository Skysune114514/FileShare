import { createApp } from 'vue'
/** 应用入口：挂载 Vue 根组件、注册路由、引入 gallery-shell 全局样式。 */
import App from './App.vue'
import router from './router'
import './styles/gallery-shell.css'

const app = createApp(App)

app.use(router)

app.mount('#app')
