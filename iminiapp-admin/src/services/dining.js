import http from './http'
const root='/admin/dining'
export const getDiningStores=()=>http.get(`${root}/stores`);export const createDiningStore=d=>http.post(`${root}/stores`,d);export const updateDiningStore=(id,d)=>http.put(`${root}/stores/${id}`,d)
export const getDiningTables=p=>http.get(`${root}/tables`,{params:p});export const createDiningTable=d=>http.post(`${root}/tables`,d);export const updateDiningTable=(id,d)=>http.put(`${root}/tables/${id}`,d)
export const getDiningCategories=p=>http.get(`${root}/categories`,{params:p});export const createDiningCategory=d=>http.post(`${root}/categories`,d);export const updateDiningCategory=(id,d)=>http.put(`${root}/categories/${id}`,d)
export const getDiningDishes=p=>http.get(`${root}/dishes`,{params:p});export const createDiningDish=d=>http.post(`${root}/dishes`,d);export const updateDiningDish=(id,d)=>http.put(`${root}/dishes/${id}`,d);export const adjustDiningStock=(id,d)=>http.post(`${root}/dishes/${id}/stock-adjustments`,d)
export const getDiningPackages=p=>http.get(`${root}/packages`,{params:p});export const createDiningPackage=d=>http.post(`${root}/packages`,d);export const updateDiningPackage=(id,d)=>http.put(`${root}/packages/${id}`,d)
export const getDiningOrders=p=>http.get(`${root}/orders`,{params:p});export const getKitchenTickets=p=>http.get(`${root}/kitchen-tickets`,{params:p});export const updateKitchenTicket=(id,d)=>http.post(`${root}/kitchen-tickets/${id}/status`,d)
export const consumeDiningVerification=d=>http.post(`${root}/verifications/consume`,d);export const startDiningDelivery=id=>http.post(`${root}/orders/${id}/delivering`);export const completeDiningOrder=id=>http.post(`${root}/orders/${id}/complete`)
export const getDiningOverview=p=>http.get(`${root}/reports/overview`,{params:p});export const getDiningDishSales=p=>http.get(`${root}/reports/dish-sales`,{params:p});export const exportDiningCsv=p=>http.get(`${root}/reports/orders.csv`,{params:p,responseType:'blob'})
