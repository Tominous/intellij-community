// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import Vue from "vue"
import Router, {RouteConfig} from "vue-router"
import {Notification} from "element-ui"
import Main from "@/views/Main.vue"
import ActivityChart from "@/views/ActivityChart.vue"
import {chartDescriptors} from "@/charts/ActivityChartDescriptor"
import TimelineChart from "@/timeline/TimelineChart.vue"

Vue.use(Router)

// to simplify development
const chartComponentRoutes: Array<RouteConfig> = chartDescriptors.map(it => {
  return {
    path: `/${it.id}`,
    name: it.label,
    component: ActivityChart,
    props: {type: it.id},
  }
})

const routes: Array<RouteConfig> = [
  {
    path: "/",
    component: Main,
  },
  {
    path: `/timeline`,
    name: "Timeline",
    component: TimelineChart,
  },
  {
    path: "*",
    component: () => {
      Notification.error("Path not found")
      return Promise.reject(new Error("Path not found"))
    },
  },
]
routes.push(...chartComponentRoutes)

export default new Router({
  routes,
})
