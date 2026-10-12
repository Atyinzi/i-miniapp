import http from './http'

const root = '/admin/gate-devices'

export const getGateDevices = () => http.get(root)
export const createGateDevice = (data) => http.post(root, data)
export const updateGateDevice = (id, data) => http.put(`${root}/${id}`, data)
export const rotateGateDeviceSecret = (id) => http.post(`${root}/${id}/rotate-secret`)
